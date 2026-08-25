package com.demo.inventory_service.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A record that one event has already been handled.
 *
 * <p>The {@code eventId} is the primary key, which is the whole mechanism: a second attempt
 * to record the same event fails on the primary key rather than on an application-level
 * "have I seen this?" check that two concurrent consumers could both pass.
 *
 * <p>Crucially this row is written <em>in the same transaction as the work it describes</em>.
 * Written before, a crash loses the work and redelivery skips it. Written after, a crash
 * repeats the work. Written together, the two either both happen or neither does, which is
 * what turns at-least-once delivery into exactly-once <em>effect</em>.
 *
 * <p>Nothing prunes this table yet. It grows one row per event forever, which is fine at
 * demo volume and would need a scheduled delete beyond some retention window in anything
 * real — worth saying out loud rather than pretending it scales as-is.
 */
@Entity
@Table(name = "processed_event")
@Getter
@Setter
@NoArgsConstructor
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, length = 64, updatable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    public ProcessedEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }
}
