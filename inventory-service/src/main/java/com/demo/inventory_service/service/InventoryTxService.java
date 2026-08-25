package com.demo.inventory_service.service;

import com.demo.inventory_service.dto.InventoryRequest;
import com.demo.inventory_service.dto.InventoryResponse;
import com.demo.inventory_service.dto.ProductRequest;
import com.demo.inventory_service.dto.ReserveInventoryRequest;
import com.demo.inventory_service.exception.DuplicateSkuException;
import com.demo.inventory_service.exception.InsufficientInventoryException;
import com.demo.inventory_service.exception.InventoryNotFoundException;
import com.demo.inventory_service.exception.ProductNotFoundException;
import com.demo.inventory_service.models.Inventory;
import com.demo.inventory_service.models.Product;
import com.demo.inventory_service.models.Reservation;
import com.demo.inventory_service.models.ReservationStatus;
import com.demo.inventory_service.repository.InventoryRepository;
import com.demo.inventory_service.repository.ProductRepository;
import com.demo.inventory_service.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The transactional unit of work for every inventory operation.
 *
 * <p><strong>Why this is a separate bean from {@link InventoryService}.</strong> The
 * optimistic-lock retry has to wrap the <em>entire</em> transaction: retrying inside it
 * would just re-run against the same stale snapshot and fail again forever. So the retry
 * loop must sit outside the transaction boundary.
 *
 * <p>Spring applies {@code @Transactional} through a proxy, and a call from one method to
 * another <em>on the same bean</em> bypasses that proxy. Had the retry loop and these
 * methods shared a class, the "transactional" methods would have silently run with no
 * transaction at all -- a data-corrupting bug that no test of the happy path would catch.
 * Two beans make the boundary explicit and impossible to get wrong by accident.
 */
@Service
@RequiredArgsConstructor
public class InventoryTxService {

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;

    @Transactional
    public Product createProduct(ProductRequest request) {

        if (productRepository.findBySku(request.getSku()).isPresent()) {
            throw new DuplicateSkuException(
                    "Product with SKU already exists: " + request.getSku()
            );
        }

        Product product = new Product();
        product.setSku(request.getSku());
        product.setName(request.getName());

        return productRepository.save(product);
    }

    @Transactional
    public InventoryResponse addInventory(InventoryRequest request) {

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ProductNotFoundException(
                        "Product not found: productId=" + request.getProductId()
                ));

        Inventory inventory = inventoryRepository
                .findByProductIdAndWarehouseId(product.getId(), request.getWarehouseId())
                .orElseGet(() -> {
                    Inventory created = new Inventory();
                    created.setProductId(product.getId());
                    created.setWarehouseId(request.getWarehouseId());
                    created.setAvailableQuantity(0);
                    created.setReservedQuantity(0);
                    return created;
                });

        inventory.setAvailableQuantity(
                inventory.getAvailableQuantity() + request.getQuantity()
        );

        return toResponse(inventoryRepository.save(inventory));
    }

    @Transactional(readOnly = true)
    public InventoryResponse getInventory(Long productId, String warehouseId) {
        return toResponse(loadInventory(productId, warehouseId));
    }

    /**
     * Reserve stock for one order line.
     *
     * <p>Idempotent by construction: if a reservation already exists for this
     * (orderId, productId, warehouseId) the call is a no-op that reports current stock. That
     * is what makes at-least-once delivery safe -- Kafka redelivering {@code OrderPlaced}
     * cannot double-reserve.
     */
    @Transactional
    public InventoryResponse reserve(ReserveInventoryRequest request) {

        Optional<Reservation> existing = reservationRepository
                .findByOrderIdAndProductIdAndWarehouseId(
                        request.getOrderId(),
                        request.getProductId(),
                        request.getWarehouseId()
                );

        if (existing.isPresent()) {
            // Already reserved, released or confirmed -- all terminal as far as this
            // request is concerned. Report current stock without touching anything.
            return toResponse(loadInventory(
                    request.getProductId(),
                    request.getWarehouseId()
            ));
        }

        Inventory inventory = loadInventory(
                request.getProductId(),
                request.getWarehouseId()
        );

        if (inventory.getAvailableQuantity() < request.getQuantity()) {
            throw new InsufficientInventoryException(
                    "Insufficient inventory for productId=" + request.getProductId()
                            + ", warehouseId=" + request.getWarehouseId()
                            + ". Available=" + inventory.getAvailableQuantity()
                            + ", requested=" + request.getQuantity()
            );
        }

        inventory.setAvailableQuantity(
                inventory.getAvailableQuantity() - request.getQuantity()
        );
        inventory.setReservedQuantity(
                inventory.getReservedQuantity() + request.getQuantity()
        );
        inventoryRepository.save(inventory);

        Reservation reservation = new Reservation();
        reservation.setOrderId(request.getOrderId());
        reservation.setProductId(request.getProductId());
        reservation.setWarehouseId(request.getWarehouseId());
        reservation.setQuantity(request.getQuantity());
        reservation.setStatus(ReservationStatus.RESERVED);

        // saveAndFlush, not save: forces the @Version check and the unique-constraint check
        // to happen here rather than at commit, so the caller's retry loop sees a failure it
        // can actually act on instead of an exception thrown after the method returned.
        reservationRepository.saveAndFlush(reservation);

        return toResponse(inventory);
    }

    /**
     * Saga compensation: release everything this order is holding.
     *
     * <p>Only RESERVED rows are touched, so calling this twice is harmless -- the second
     * call finds nothing to do and returns an empty list. An unknown orderId is likewise a
     * no-op rather than a 404: compensation for an order that never reserved anything has
     * genuinely succeeded.
     */
    @Transactional
    public List<InventoryResponse> releaseByOrderId(String orderId) {

        List<Reservation> reservations = reservationRepository
                .findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED);

        List<InventoryResponse> responses = new ArrayList<>();

        for (Reservation reservation : reservations) {

            Inventory inventory = loadInventory(
                    reservation.getProductId(),
                    reservation.getWarehouseId()
            );

            inventory.setReservedQuantity(
                    inventory.getReservedQuantity() - reservation.getQuantity()
            );
            inventory.setAvailableQuantity(
                    inventory.getAvailableQuantity() + reservation.getQuantity()
            );
            inventoryRepository.save(inventory);

            reservation.setStatus(ReservationStatus.RELEASED);
            reservationRepository.save(reservation);

            responses.add(toResponse(inventory));
        }

        return responses;
    }

    /**
     * The order completed: the stock has physically left the warehouse.
     *
     * <p>Reserved quantity drops and available is <em>not</em> restored -- that asymmetry
     * with {@link #releaseByOrderId} is the whole difference between a shipment and a
     * cancellation.
     */
    @Transactional
    public List<InventoryResponse> confirmByOrderId(String orderId) {

        List<Reservation> reservations = reservationRepository
                .findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED);

        List<InventoryResponse> responses = new ArrayList<>();

        for (Reservation reservation : reservations) {

            Inventory inventory = loadInventory(
                    reservation.getProductId(),
                    reservation.getWarehouseId()
            );

            inventory.setReservedQuantity(
                    inventory.getReservedQuantity() - reservation.getQuantity()
            );
            inventoryRepository.save(inventory);

            reservation.setStatus(ReservationStatus.CONFIRMED);
            reservationRepository.save(reservation);

            responses.add(toResponse(inventory));
        }

        return responses;
    }

    private Inventory loadInventory(Long productId, String warehouseId) {
        return inventoryRepository
                .findByProductIdAndWarehouseId(productId, warehouseId)
                .orElseThrow(() -> new InventoryNotFoundException(
                        "Inventory not found for productId=" + productId
                                + ", warehouseId=" + warehouseId
                ));
    }

    private InventoryResponse toResponse(Inventory inventory) {
        return new InventoryResponse(
                inventory.getProductId(),
                inventory.getWarehouseId(),
                inventory.getAvailableQuantity(),
                inventory.getReservedQuantity()
        );
    }
}
