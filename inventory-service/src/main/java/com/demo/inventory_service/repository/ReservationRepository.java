package com.demo.inventory_service.repository;

import com.demo.inventory_service.models.Reservation;
import com.demo.inventory_service.models.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** The idempotency lookup: has this exact order line already been reserved? */
    Optional<Reservation> findByOrderIdAndProductIdAndWarehouseId(
            String orderId,
            Long productId,
            String warehouseId
    );

    /** Saga compensation: everything this order is holding, whatever its status. */
    List<Reservation> findByOrderId(String orderId);

    List<Reservation> findByOrderIdAndStatus(String orderId, ReservationStatus status);
}
