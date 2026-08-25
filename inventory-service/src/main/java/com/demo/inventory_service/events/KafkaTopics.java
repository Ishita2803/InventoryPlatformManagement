package com.demo.inventory_service.events;

/**
 * Topic names.
 *
 * <p>Duplicated in order-service rather than shared through a common module. A shared
 * "events" jar is the classic way to couple services that are supposed to be independently
 * deployable: every contract change forces a lockstep release of everything that depends on
 * it. The cost of duplication is that both copies must be changed together; the cost of
 * sharing is that neither service can be deployed alone.
 */
public final class KafkaTopics {

    public static final String ORDER_PLACED = "order.placed";
    public static final String INVENTORY_RESERVED = "inventory.reserved";
    public static final String INVENTORY_FAILED = "inventory.failed";

    private KafkaTopics() {
    }
}
