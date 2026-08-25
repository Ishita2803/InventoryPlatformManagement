package com.demo.inventory_service.kafka;

import com.demo.inventory_service.events.KafkaTopics;
import com.demo.inventory_service.models.Inventory;
import com.demo.inventory_service.models.Product;
import com.demo.inventory_service.repository.InventoryRepository;
import com.demo.inventory_service.repository.ProcessedEventRepository;
import com.demo.inventory_service.repository.ProductRepository;
import com.demo.inventory_service.repository.ReservationRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A message this service cannot parse must end up somewhere a human can find it, and must
 * not block the messages behind it.
 *
 * <p>This is the failure mode that quietly ruins event-driven systems: one malformed payload
 * at the head of a partition, retried forever, and every good message behind it stops.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true"
})
@EmbeddedKafka(
        partitions = 1,
        topics = {
                KafkaTopics.ORDER_PLACED,
                KafkaTopics.ORDER_PLACED + ".DLT",
                KafkaTopics.INVENTORY_RESERVED,
                KafkaTopics.INVENTORY_FAILED
        }
)
class DeadLetterIT {

    private static final String WAREHOUSE_ID = "WH-1";
    private static final Duration TIMEOUT = Duration.ofSeconds(45);

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    private Long productId;
    private Consumer<String, String> dltConsumer;

    @BeforeEach
    void setUp() {
        processedEventRepository.deleteAll();
        reservationRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();

        Product product = new Product();
        product.setSku("SKU-" + UUID.randomUUID());
        product.setName("Widget");
        productId = productRepository.save(product).getId();

        Inventory inventory = new Inventory();
        inventory.setProductId(productId);
        inventory.setWarehouseId(WAREHOUSE_ID);
        inventory.setAvailableQuantity(10);
        inventory.setReservedQuantity(0);
        inventoryRepository.save(inventory);

        Map<String, Object> props = KafkaTestUtils.consumerProps(
                broker.getBrokersAsString(), "dlt-observer-" + UUID.randomUUID(), "true");
        dltConsumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(
                props,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer());
        broker.consumeFromEmbeddedTopics(dltConsumer, KafkaTopics.ORDER_PLACED + ".DLT");
    }

    @AfterEach
    void tearDown() {
        if (dltConsumer != null) {
            dltConsumer.close();
        }
    }

    @Test
    @DisplayName("a payload that cannot be parsed lands in the DLT instead of looping forever")
    void poisonMessageGoesToTheDlt() {

        String key = "poison-" + UUID.randomUUID();
        sendRaw(key, "{ this is not valid json at all ");

        ConsumerRecord<String, String> dead = awaitDlt(key);

        // Note the payload is JSON-encoded on the way to the DLT: the producer's value
        // serializer is JsonSerializer and the value being republished is already a String,
        // so it arrives quoted and escaped. Slightly awkward to read, but lossless -- the
        // original bytes are recoverable.
        assertThat(dead.value()).contains("not valid json");

        // Spring records why it gave up, which is what makes a DLT worth having.
        String exceptionType = header(dead, "kafka_dlt-exception-fqcn");
        assertThat(exceptionType).isNotBlank();
        assertThat(header(dead, "kafka_dlt-original-topic")).isEqualTo(KafkaTopics.ORDER_PLACED);
    }

    @Test
    @DisplayName("a poison message does not block the good messages behind it")
    void goodMessagesStillFlowAfterAPoisonOne() {

        String orderId = UUID.randomUUID().toString();

        // Poison first, valid second, same partition — so the second is genuinely queued
        // behind the first rather than running in parallel.
        sendRaw("poison-" + UUID.randomUUID(), "}}} broken {{{");
        sendRaw(orderId, """
                {"eventId":"%s","orderId":"%s","customerId":"CUST-1",
                 "lines":[{"productId":%d,"warehouseId":"%s","quantity":3}],
                 "occurredAt":"2026-08-26T10:00:00Z"}
                """.formatted(UUID.randomUUID(), orderId, productId, WAREHOUSE_ID));

        // If the poison message were retried indefinitely, this would never happen.
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        boolean reserved = false;
        while (System.currentTimeMillis() < deadline) {
            Inventory current = inventoryRepository
                    .findByProductIdAndWarehouseId(productId, WAREHOUSE_ID).orElseThrow();
            if (current.getReservedQuantity() == 3) {
                reserved = true;
                break;
            }
        }

        assertThat(reserved)
                .as("the valid message behind the poison one must still be processed")
                .isTrue();

        assertThat(reservationRepository.findByOrderId(orderId)).hasSize(1);
    }

    private void sendRaw(String key, String rawJson) {

        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());

        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(KafkaTopics.ORDER_PLACED, key, rawJson));
            producer.flush();
        }
    }

    /**
     * Waits for the DLT record with this key specifically. Matching on key rather than
     * taking the first record matters: the consumer reads the topic from the beginning, so
     * it also sees whatever earlier tests in this class dead-lettered.
     */
    private ConsumerRecord<String, String> awaitDlt(String expectedKey) {

        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();

        while (System.currentTimeMillis() < deadline) {
            var records = dltConsumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (expectedKey.equals(record.key())) {
                    return record;
                }
            }
        }

        throw new AssertionError("Nothing arrived in " + KafkaTopics.ORDER_PLACED
                + ".DLT within " + TIMEOUT);
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value());
    }
}
