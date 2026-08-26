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

    /**
     * The Saga's settlement step, published by order-service once payment has answered.
     * Inventory listens and either confirms the reservation (stock has shipped) or releases
     * it (compensation). Doing this by event rather than a REST call from order-service
     * means compensation survives inventory-service being briefly down.
     */
    public static final String ORDER_CONFIRMED = "order.confirmed";
    public static final String ORDER_CANCELLED = "order.cancelled";

    private KafkaTopics() {
    }
}
