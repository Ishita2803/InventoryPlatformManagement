package com.demo.inventory_service.reconciliation;

import com.demo.inventory_service.events.KafkaTopics;
import com.demo.inventory_service.models.Inventory;
import com.demo.inventory_service.models.Product;
import com.demo.inventory_service.models.Reservation;
import com.demo.inventory_service.models.ReservationStatus;
import com.demo.inventory_service.repository.InventoryRepository;
import com.demo.inventory_service.repository.ProductRepository;
import com.demo.inventory_service.repository.ReservationRepository;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Recovering settlements that were dead-lettered.
 *
 * <p>This reproduces the real incident rather than an approximation. Benchmark run 1 pushed
 * 200 orders at one product; nine confirmations exhausted the optimistic-lock retry budget and
 * their records landed in {@code order.confirmed.DLT}. The reservations stayed
 * {@code RESERVED} and nine units of stock were held for ever against orders that
 * order-service had already marked {@code CONFIRMED} — because nothing in the system ever read
 * a dead-letter topic.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        // The normal listeners stay off: this test is about the recovery drain, and a live
        // listener consuming the real topics would muddy what is being asserted.
        "spring.kafka.listener.auto-startup=false",
        "settlement-recovery.poll-timeout-ms=5000"
})
@EmbeddedKafka(
        partitions = 1,
        topics = {KafkaTopics.ORDER_CONFIRMED + ".DLT", KafkaTopics.ORDER_CANCELLED + ".DLT"}
)
class SettlementRecoveryIT {

    @Autowired
    private SettlementRecoveryService recoveryService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EmbeddedKafkaBroker broker;

    private Long productId;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        inventoryRepository.deleteAll();
        productRepository.deleteAll();

        Product product = new Product();
        product.setSku("RECOVER-" + UUID.randomUUID());
        product.setName("Recoverable Widget");
        productId = productRepository.saveAndFlush(product).getId();
    }

    @Test
    @DisplayName("a dead-lettered confirmation is replayed, and the held stock is shipped")
    void replaysDeadLetteredConfirmation() {

        String orderId = UUID.randomUUID().toString();
        stockWithReservation(orderId, 10, 2);

        publishDeadLetter(KafkaTopics.ORDER_CONFIRMED + ".DLT", orderId);

        RecoveryReport report = recoveryService.recover();

        assertThat(report.confirmed()).isEqualTo(1);
        assertThat(statusOf(orderId)).isEqualTo(ReservationStatus.CONFIRMED);

        Inventory inventory = inventory();
        // Confirming ships the stock: reserved drops and available does NOT rise. That
        // asymmetry against a release is the whole difference between a sale and a refund.
        assertThat(inventory.getReservedQuantity()).isZero();
        assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("a dead-lettered cancellation is replayed, and the stock comes back")
    void replaysDeadLetteredCancellation() {

        String orderId = UUID.randomUUID().toString();
        stockWithReservation(orderId, 10, 2);

        publishDeadLetter(KafkaTopics.ORDER_CANCELLED + ".DLT", orderId);

        RecoveryReport report = recoveryService.recover();

        assertThat(report.released()).isEqualTo(1);
        assertThat(statusOf(orderId)).isEqualTo(ReservationStatus.RELEASED);

        Inventory inventory = inventory();
        assertThat(inventory.getReservedQuantity()).isZero();
        assertThat(inventory.getAvailableQuantity()).isEqualTo(12);
    }

    @Test
    @DisplayName("replaying a settlement that already happened changes nothing")
    void replayIsIdempotent() {

        String orderId = UUID.randomUUID().toString();
        stockWithReservation(orderId, 10, 2);
        publishDeadLetter(KafkaTopics.ORDER_CONFIRMED + ".DLT", orderId);

        recoveryService.recover();
        Inventory afterFirst = inventory();

        // A second dead letter for the same order — which is exactly what a redelivery of an
        // already-recovered record looks like.
        publishDeadLetter(KafkaTopics.ORDER_CONFIRMED + ".DLT", orderId);
        RecoveryReport second = recoveryService.recover();

        assertThat(second.confirmed())
                .as("nothing is still RESERVED, so the replay matches no rows")
                .isZero();

        Inventory afterSecond = inventory();
        assertThat(afterSecond.getAvailableQuantity())
                .isEqualTo(afterFirst.getAvailableQuantity());
        assertThat(afterSecond.getReservedQuantity())
                .isEqualTo(afterFirst.getReservedQuantity());
    }

    @Test
    @DisplayName("an empty dead-letter topic is a quiet no-op")
    void emptyTopicIsQuiet() {

        RecoveryReport report = recoveryService.recover();

        assertThat(report.examined()).isZero();
        assertThat(report.needsAttention()).isFalse();
    }

    @Test
    @DisplayName("committed offsets mean a recovered record is not replayed on the next sweep")
    void commitsItsProgress() {

        String orderId = UUID.randomUUID().toString();
        stockWithReservation(orderId, 10, 2);
        publishDeadLetter(KafkaTopics.ORDER_CONFIRMED + ".DLT", orderId);

        assertThat(recoveryService.recover().examined()).isEqualTo(1);

        // Without commitSync the same record would be read for ever, and every sweep would
        // report work it had already done.
        assertThat(recoveryService.recover().examined())
                .as("the offset was committed, so there is nothing left to read")
                .isZero();
    }

    // ------------------------------------------------------------------ helpers

    private void stockWithReservation(String orderId, int available, int reserved) {

        Inventory inventory = new Inventory();
        inventory.setProductId(productId);
        inventory.setWarehouseId("WH-1");
        inventory.setAvailableQuantity(available);
        inventory.setReservedQuantity(reserved);
        inventoryRepository.saveAndFlush(inventory);

        Reservation reservation = new Reservation();
        reservation.setOrderId(orderId);
        reservation.setProductId(productId);
        reservation.setWarehouseId("WH-1");
        reservation.setQuantity(reserved);
        reservation.setStatus(ReservationStatus.RESERVED);
        reservationRepository.saveAndFlush(reservation);
    }

    /**
     * Publishes a raw JSON payload, exactly as {@code DeadLetterPublishingRecoverer} does —
     * the original record's value, unchanged.
     */
    private void publishDeadLetter(String topic, String orderId) {

        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        String payload = "{\"eventId\":\"" + UUID.randomUUID()
                + "\",\"orderId\":\"" + orderId
                + "\",\"paymentId\":\"pay-1\",\"reason\":null}";

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, orderId, payload));
            producer.flush();
        }
    }

    private ReservationStatus statusOf(String orderId) {
        return reservationRepository.findAll().stream()
                .filter(reservation -> reservation.getOrderId().equals(orderId))
                .findFirst()
                .orElseThrow()
                .getStatus();
    }

    private Inventory inventory() {
        return inventoryRepository.findAll().getFirst();
    }
}
