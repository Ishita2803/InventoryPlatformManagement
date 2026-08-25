package com.demo.order_service.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An event that has been decided but not yet announced.
 *
 * <p><strong>The problem this solves.</strong> Saving the order and publishing to Kafka are
 * two separate systems, so they cannot share a transaction. Commit then publish, and a crash
 * in between leaves an order nobody hears about. Publish then commit, and inventory reserves
 * stock for an order that never existed. There is no ordering of two writes that is safe.
 *
 * <p><strong>The fix.</strong> Stop writing to two systems. The event is written to
 * <em>this table, in the same transaction as the order</em> — one database, one commit, so
 * they are atomic by construction. A separate poller moves rows to Kafka afterwards.
 *
 * <p>The guarantee changes from "maybe published" to "will be published, eventually, at least
 * once". At-least-once is fine here precisely because the consumers were made idempotent in
 * Phase 4 — the outbox and idempotent consumers are two halves of one design, which is why
 * a duplicate publish is a non-event rather than a bug.
 */
@Entity
@Table(
        name = "outbox_event",
        indexes = {
                // The poller's query: PENDING rows, oldest first. Without this it is a full
                // table scan every second, over a table that only grows.
                @Index(name = "idx_outbox_status_id", columnList = "status, id"),
                @Index(name = "uk_outbox_event_id", columnList = "event_id", unique = true)
        }
)
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Matches the {@code eventId} inside the payload, so it can be traced end to end. */
    @Column(name = "event_id", nullable = false, unique = true, length = 64, updatable = false)
    private String eventId;

    @Column(name = "aggregate_type", nullable = false, length = 50, updatable = false)
    private String aggregateType;

    /** The Kafka message key — {@code orderId}, so one order keeps to one partition. */
    @Column(name = "aggregate_id", nullable = false, length = 64, updatable = false)
    private String aggregateId;

    @Column(nullable = false, length = 100, updatable = false)
    private String topic;

    /**
     * The serialized event, exactly as it will be sent.
     *
     * <p>Serialized at write time rather than reconstructed at publish time: the payload is
     * then a snapshot of what was true when the order was placed, and cannot drift if the
     * order is later modified.
     */
    @Lob
    @Column(nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    public OutboxEvent(String eventId, String aggregateType, String aggregateId,
                       String topic, String payload) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.createdAt = Instant.now();
    }

    public void markPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /** Records a failed attempt, giving up once the budget is spent. */
    public void markAttemptFailed(String error, int maxAttempts) {
        this.attempts++;
        this.lastError = error == null ? null
                : error.substring(0, Math.min(error.length(), 1000));
        if (this.attempts >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
        }
    }
}
