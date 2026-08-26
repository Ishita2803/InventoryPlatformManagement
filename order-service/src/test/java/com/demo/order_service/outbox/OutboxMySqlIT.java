package com.demo.order_service.outbox;

import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.OrderItemRequest;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.models.OutboxEvent;
import com.demo.order_service.repository.OrderRepository;
import com.demo.order_service.repository.OutboxEventRepository;
import com.demo.order_service.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema behaviour against the database this platform actually runs on.
 *
 * <p><strong>Why this class exists.</strong> Phase 8 hit a bug that no existing test could
 * have caught: {@code outbox_event.payload} was mapped to MySQL's {@code TINYTEXT} — 255
 * bytes — because {@code @Lob} on a String with no explicit length makes Hibernate pick the
 * smallest text tier. It stayed hidden from Phase 5 to Phase 8 because {@code OrderPlaced}
 * payloads happened to be about 200 characters, and it surfaced only when a cancellation
 * carried an exception message. H2 does not reproduce MySQL's type mapping, so the entire
 * H2-based suite was structurally blind to it.
 *
 * <p>These tests use the same {@code mysql:8.0} image the platform runs on. They are slower
 * than H2 — a container start per class — which is why they are targeted at the things that
 * are genuinely database-specific rather than duplicating the fast tests.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // Let Hibernate detect the dialect from the real connection instead of the H2 one
        // pinned in the shared test config.
        "spring.jpa.database-platform="
})
@Testcontainers
class OutboxMySqlIT {

    /**
     * {@code @ServiceConnection} wires the container's JDBC URL, username and password into
     * the context automatically — no {@code @DynamicPropertySource} plumbing, and no risk of
     * the test pointing at the wrong database.
     */
    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            // The data directory in RAM. MySQL's cold init measured 85-235 seconds on disk
            // here, and Testcontainers kept connecting during the entrypoint's temporary
            // server -- which listens on the socket, then shuts down and restarts -- giving
            // "Communications link failure". Exactly the trap the Compose healthcheck hit in
            // Phase 7. tmpfs cuts init to a few seconds, which closes the window, and tests
            // have no use for durability anyway.
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withStartupTimeout(Duration.ofMinutes(5));

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("the outbox payload column is a real text type, not TINYTEXT")
    void payloadColumnIsLargeEnough() {

        String columnType = jdbcTemplate.queryForObject("""
                SELECT COLUMN_TYPE FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'outbox_event'
                   AND COLUMN_NAME = 'payload'
                """, String.class);

        // The regression assertion. Before the fix this was 'tinytext' and every payload
        // over 255 bytes was rejected at insert time.
        assertThat(columnType)
                .as("payload must not be TINYTEXT — 255 bytes silently truncates events")
                .isIn("mediumtext", "longtext", "text");
    }

    @Test
    @DisplayName("an order with many lines round-trips, though its payload exceeds 255 bytes")
    void largePayloadRoundTrips() {

        // Eight lines is an ordinary order and produces a payload comfortably over the old
        // limit. Under TINYTEXT this threw "Data truncation: Data too long for column".
        OrderResponse order = orderService.createOrder(requestWithLines(8));

        List<OutboxEvent> outbox = outboxEventRepository.findAll();
        assertThat(outbox).hasSize(1);

        String payload = outbox.getFirst().getPayload();
        assertThat(payload.length())
                .as("the payload must actually exceed the old 255-byte limit, or this test proves nothing")
                .isGreaterThan(255);

        // Stored and read back intact, not silently truncated.
        assertThat(payload).contains(order.orderId());
        assertThat(payload).endsWith("}");
    }

    @Test
    @DisplayName("a cancellation carrying a long exception message also fits")
    void longCancellationReasonFits() {

        OrderResponse order = orderService.createOrder(requestWithLines(1));

        // Roughly what a real stack-trace-derived reason looks like. OutboxWriter bounds it
        // at 500 characters; this checks the column accepts that comfortably.
        String longReason = "Payment unavailable: " + "org.springframework.web.client."
                + "ResourceAccessException: I/O error on POST request for "
                + "\"http://payment-service:8084/api/payments\": Connection refused ".repeat(6);

        outboxEventRepository.deleteAll();
        // settleOrder is the path that writes it; drive it directly.
        assertThat(longReason.length()).isGreaterThan(255);

        var written = jdbcTemplate.update("""
                INSERT INTO outbox_event (event_id, aggregate_type, aggregate_id, topic,
                                          payload, status, attempts, created_at)
                VALUES (?, 'Order', ?, 'order.cancelled', ?, 'PENDING', 0, NOW())
                """, "evt-long", order.orderId(), longReason);

        assertThat(written).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_id = 'evt-long'", String.class))
                .hasSize(longReason.length());
    }

    private CreateOrderRequest requestWithLines(int lines) {

        List<OrderItemRequest> items = new ArrayList<>();
        for (int i = 1; i <= lines; i++) {
            OrderItemRequest item = new OrderItemRequest();
            item.setProductId((long) i);
            item.setWarehouseId("WAREHOUSE-" + i);
            item.setQuantity(i);
            item.setUnitPrice(new BigDecimal("10.50"));
            items.add(item);
        }

        CreateOrderRequest request = new CreateOrderRequest();
        request.setCustomerId("CUST-TESTCONTAINERS");
        request.setItems(items);
        return request;
    }
}
