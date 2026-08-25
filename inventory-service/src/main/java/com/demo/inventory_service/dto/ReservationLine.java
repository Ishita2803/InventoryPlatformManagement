package com.demo.inventory_service.dto;

/**
 * One line to reserve. Deliberately not the Kafka event's {@code Line} type — the service
 * layer should not have to import the messaging contract in order to be callable.
 */
public record ReservationLine(
        Long productId,
        String warehouseId,
        Integer quantity
) {
}
