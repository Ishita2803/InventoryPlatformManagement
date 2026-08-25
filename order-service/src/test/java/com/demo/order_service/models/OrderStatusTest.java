package com.demo.order_service.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.demo.order_service.models.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lifecycle rules, pinned before any Kafka consumer starts depending on them.
 */
class OrderStatusTest {

    @Test
    @DisplayName("a pending order may reserve, fail on stock, or be cancelled")
    void pendingTransitions() {
        assertThat(PENDING.canTransitionTo(INVENTORY_RESERVED)).isTrue();
        assertThat(PENDING.canTransitionTo(INVENTORY_FAILED)).isTrue();
        assertThat(PENDING.canTransitionTo(CANCELLED)).isTrue();

        // Nothing skips straight to confirmed: payment has not happened yet.
        assertThat(PENDING.canTransitionTo(CONFIRMED)).isFalse();
        assertThat(PENDING.canTransitionTo(PENDING)).isFalse();
    }

    @Test
    @DisplayName("a reserved order may be confirmed or cancelled, but cannot go back to pending")
    void reservedTransitions() {
        assertThat(INVENTORY_RESERVED.canTransitionTo(CONFIRMED)).isTrue();
        assertThat(INVENTORY_RESERVED.canTransitionTo(CANCELLED)).isTrue();
        assertThat(INVENTORY_RESERVED.canTransitionTo(PENDING)).isFalse();
        assertThat(INVENTORY_RESERVED.canTransitionTo(INVENTORY_FAILED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"CONFIRMED", "INVENTORY_FAILED", "CANCELLED"})
    @DisplayName("terminal states accept nothing further, so a replayed event cannot revive an order")
    void terminalStatesAcceptNothing(OrderStatus terminal) {

        assertThat(terminal.isTerminal()).isTrue();
        assertThat(terminal.allowedNextStates()).isEmpty();

        for (OrderStatus target : OrderStatus.values()) {
            assertThat(terminal.canTransitionTo(target))
                    .as("%s should not be able to move to %s", terminal, target)
                    .isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "INVENTORY_RESERVED"})
    @DisplayName("non-terminal states are not marked terminal")
    void nonTerminalStates(OrderStatus status) {
        assertThat(status.isTerminal()).isFalse();
        assertThat(status.allowedNextStates()).isNotEmpty();
    }
}
