package com.demo.order_service.repository;

import com.demo.order_service.models.OutboxEvent;
import com.demo.order_service.models.OutboxStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * The poller's query: oldest pending rows first, capped.
     *
     * <p>Ordered by id so events publish in the order they were created. Capped so one poll
     * cannot pick up a backlog of a million rows and stall on it.
     */
    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Limit limit);

    Optional<OutboxEvent> findByEventId(String eventId);

    long countByStatus(OutboxStatus status);
}
