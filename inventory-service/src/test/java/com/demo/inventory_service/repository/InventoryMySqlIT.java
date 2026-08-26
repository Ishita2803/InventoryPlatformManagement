package com.demo.inventory_service.repository;

import com.demo.inventory_service.models.Inventory;
import com.demo.inventory_service.models.Reservation;
import com.demo.inventory_service.models.ReservationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The constraints this service's correctness rests on, checked against real MySQL.
 *
 * <p>The unique constraint on (order_id, product_id, warehouse_id) *is* the idempotency
 * guarantee — everything about not double-reserving depends on the database actually
 * enforcing it. H2 enforces its own idea of a unique constraint, which is usually the same
 * but is not the thing that runs in production. After the TINYTEXT episode in Phase 8, "the
 * schema Hibernate generates on MySQL" is treated as something to verify rather than assume.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform="
})
@Testcontainers
class InventoryMySqlIT {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            // Data directory in RAM: on-disk init measured 85-235s here and races the
            // entrypoint's temporary server. See order-service's OutboxMySqlIT.
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withStartupTimeout(Duration.ofMinutes(5));

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        inventoryRepository.deleteAll();
    }

    @Test
    @DisplayName("MySQL really enforces the reservation uniqueness that idempotency relies on")
    void uniqueConstraintIsEnforced() {

        String orderId = UUID.randomUUID().toString();

        reservationRepository.saveAndFlush(reservation(orderId, 1L, "WH-1"));

        // Same order, same product, same warehouse: the second insert must be rejected by
        // the database, not merely by application logic that two threads could both pass.
        assertThatThrownBy(() ->
                reservationRepository.saveAndFlush(reservation(orderId, 1L, "WH-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the same product for a DIFFERENT order is allowed")
    void differentOrdersMayReserveTheSameProduct() {

        reservationRepository.saveAndFlush(reservation(UUID.randomUUID().toString(), 1L, "WH-1"));
        reservationRepository.saveAndFlush(reservation(UUID.randomUUID().toString(), 1L, "WH-1"));

        assertThat(reservationRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("the constraint exists in the generated schema, on the expected columns")
    void constraintIsInTheSchema() {

        Integer columns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'reservation'
                   AND NON_UNIQUE = 0
                   AND COLUMN_NAME IN ('order_id', 'product_id', 'warehouse_id')
                """, Integer.class);

        assertThat(columns)
                .as("all three columns must take part in a unique index")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("the @Version column is generated, which is what prevents overselling")
    void versionColumnExists() {

        Integer versionColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'inventory'
                   AND COLUMN_NAME = 'version'
                """, Integer.class);

        assertThat(versionColumns).isEqualTo(1);
    }

    private Reservation reservation(String orderId, Long productId, String warehouseId) {
        Reservation reservation = new Reservation();
        reservation.setOrderId(orderId);
        reservation.setProductId(productId);
        reservation.setWarehouseId(warehouseId);
        reservation.setQuantity(1);
        reservation.setStatus(ReservationStatus.RESERVED);
        return reservation;
    }
}
