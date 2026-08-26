package com.demo.inventory_service.reconciliation;

import com.demo.inventory_service.events.KafkaTopics;
import com.demo.inventory_service.service.InventoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-applies settlements that were dead-lettered, so a transient failure does not strand stock
 * for ever.
 *
 * <h2>The failure this exists for, which actually happened</h2>
 *
 * <p>Benchmark run 1 sent 200 orders at a <em>single</em> product, so every confirmation
 * contended for the same {@code inventory} row. Nine of them exhausted the four-attempt
 * optimistic-lock retry budget, the listener's error handler retried three more times and gave
 * up, and the records went to {@code order.confirmed.DLT}.
 *
 * <p>Every one of those nine reservations stayed {@code RESERVED}. Nine units of stock were
 * held against orders that order-service had already marked {@code CONFIRMED} — permanently,
 * because <strong>nothing in this system ever read a dead-letter topic.</strong> The DLTs were
 * write-only: excellent at stopping a poison message from blocking a partition, and a
 * guaranteed leak of anything that landed in one.
 *
 * <p>The order-side {@code OrderReconciliationService} cannot see this. It sweeps orders stuck
 * before settlement; these orders settled perfectly. The drift is entirely on this side.
 *
 * <h2>Why replaying is safe</h2>
 *
 * <p>{@code confirmByOrderId} and {@code releaseByOrderId} both select
 * {@code WHERE status = RESERVED}. A reservation that was already settled matches nothing and
 * the replay is a no-op. That natural idempotency is what makes it safe to re-apply a record
 * whose original outcome is unknown — which is exactly the situation a dead letter leaves you
 * in.
 *
 * <h2>Why a scheduled drain rather than a listener on the DLT</h2>
 *
 * <p>A {@code @KafkaListener} on the dead-letter topic would consume each record within
 * milliseconds of it arriving — while whatever caused the failure is still happening. It would
 * fail again immediately, and a hot retry loop against a contended row is worse than the
 * original problem. Draining on a timer gives the transient condition time to pass, which for
 * lock contention is all that was ever needed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SettlementRecoveryService {

    private static final String CONFIRMED_DLT = KafkaTopics.ORDER_CONFIRMED + ".DLT";
    private static final String CANCELLED_DLT = KafkaTopics.ORDER_CANCELLED + ".DLT";

    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;

    // Read straight from the property rather than via KafkaProperties: Spring Boot 4
    // relocated that class out of org.springframework.boot.autoconfigure.kafka, and this
    // needs exactly one value from it.
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${settlement-recovery.group-id:inventory-service-dlt-recovery}")
    private String groupId;

    @Value("${settlement-recovery.poll-timeout-ms:5000}")
    private long pollTimeoutMs;

    @Value("${settlement-recovery.max-records:100}")
    private int maxRecords;

    @Scheduled(
            fixedDelayString = "${settlement-recovery.interval-ms:120000}",
            initialDelayString = "${settlement-recovery.initial-delay-ms:120000}"
    )
    public void recoverScheduled() {
        try {
            recover();
        } catch (Exception unexpected) {
            // Must never be able to kill its own schedule — this job's value is being there
            // months later, the one time something goes wrong.
            log.error("Dead-letter recovery sweep failed; will retry next interval", unexpected);
        }
    }

    /**
     * Drains one batch from the settlement dead-letter topics.
     *
     * <p>Offsets are committed only up to the last record that succeeded. A record that fails
     * again stops the drain where it is and is retried next sweep, rather than being skipped —
     * skipping is what produced the stranded stock in the first place.
     */
    public RecoveryReport recover() {

        try (Consumer<String, String> consumer = createConsumer()) {

            consumer.subscribe(List.of(CONFIRMED_DLT, CANCELLED_DLT));

            ConsumerRecords<String, String> records =
                    consumer.poll(Duration.ofMillis(pollTimeoutMs));

            if (records.isEmpty()) {
                return RecoveryReport.empty();
            }

            int confirmed = 0;
            int released = 0;
            int failed = 0;

            Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>();
            List<TopicPartition> stopped = new ArrayList<>();

            for (ConsumerRecord<String, String> record : records) {

                TopicPartition partition = new TopicPartition(record.topic(), record.partition());

                // This partition already hit a failure in this sweep. Stop here so ordering
                // is preserved and nothing after the failure is silently applied first.
                if (stopped.contains(partition)) {
                    continue;
                }

                try {
                    int lines = apply(record);

                    if (CONFIRMED_DLT.equals(record.topic())) {
                        confirmed += lines;
                    } else {
                        released += lines;
                    }

                    commits.put(partition, new OffsetAndMetadata(record.offset() + 1));

                } catch (Exception stillFailing) {
                    failed++;
                    stopped.add(partition);
                    log.warn("Dead-lettered settlement for offset {} of {} still fails; leaving "
                                    + "it for the next sweep: {}",
                            record.offset(), record.topic(), stillFailing.toString());
                }
            }

            if (!commits.isEmpty()) {
                consumer.commitSync(commits);
            }

            RecoveryReport report = new RecoveryReport(
                    records.count(), confirmed, released, failed);

            log.info("Dead-letter recovery: {}", report);
            return report;
        }
    }

    /**
     * Applies one dead-lettered settlement.
     *
     * <p>Reads the payload as a tree rather than binding it to an event class. The recovery
     * only needs the aggregate identifier — the topic already carries the decision of what to
     * do with it — and a dead letter is exactly the place where a payload might not match the
     * current class shape. Failing to deserialise is how the record got here in some cases.
     */
    private int apply(ConsumerRecord<String, String> record) throws Exception {

        JsonNode payload = objectMapper.readTree(record.value());
        JsonNode orderId = payload.get("orderId");

        if (orderId == null || orderId.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Dead-lettered record has no orderId: " + record.value());
        }

        String id = orderId.asText();

        if (CONFIRMED_DLT.equals(record.topic())) {
            int lines = inventoryService.confirmReservation(id).size();
            log.info("Recovered dead-lettered confirmation for order {} — {} line(s)", id, lines);
            return lines;
        }

        int lines = inventoryService.releaseInventory(id).size();
        log.info("Recovered dead-lettered release for order {} — {} line(s)", id, lines);
        return lines;
    }

    private Consumer<String, String> createConsumer() {

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Deliberately String, not JSON: the payload is read as a tree, so a record that was
        // dead-lettered *because* it would not deserialise can still be inspected.
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxRecords);

        return new KafkaConsumer<>(props);
    }
}
