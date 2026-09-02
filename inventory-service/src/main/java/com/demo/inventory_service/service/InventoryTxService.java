package com.demo.inventory_service.service;

import com.demo.inventory_service.dto.FulfillmentRequest;
import com.demo.inventory_service.dto.FulfillmentResponse;
import com.demo.inventory_service.dto.InventoryRequest;
import com.demo.inventory_service.dto.InventoryResponse;
import com.demo.inventory_service.dto.ProductRequest;
import com.demo.inventory_service.dto.ReservationLine;
import com.demo.inventory_service.dto.ReserveInventoryRequest;
import com.demo.inventory_service.dto.ReserveOutcome;
import com.demo.inventory_service.exception.CatalogItemNotFoundException;
import com.demo.inventory_service.exception.DuplicateSkuException;
import com.demo.inventory_service.exception.InsufficientInventoryException;
import com.demo.inventory_service.exception.InventoryNotFoundException;
import com.demo.inventory_service.exception.ProductNotFoundException;
import com.demo.inventory_service.models.CatalogItem;
import com.demo.inventory_service.models.Inventory;
import com.demo.inventory_service.models.Product;
import com.demo.inventory_service.models.ProcessedEvent;
import com.demo.inventory_service.models.Reservation;
import com.demo.inventory_service.models.ReservationStatus;
import com.demo.inventory_service.models.Warehouse;
import com.demo.inventory_service.repository.CatalogItemRepository;
import com.demo.inventory_service.repository.InventoryRepository;
import com.demo.inventory_service.repository.ProcessedEventRepository;
import com.demo.inventory_service.repository.ProductRepository;
import com.demo.inventory_service.repository.ReservationRepository;
import com.demo.inventory_service.repository.WarehouseRepository;
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
    private final ProcessedEventRepository processedEventRepository;
    private final CatalogItemRepository catalogItemRepository;
    private final WarehouseRepository warehouseRepository;

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

        applyReservation(inventory, request.getOrderId(), request.getQuantity());

        return toResponse(inventory);
    }

    /**
     * The actual stock move a reservation makes, factored out so Phase D7's fulfillment
     * search -- which reserves a caller-computed (already-known-available) quantity across
     * several warehouses in one order -- can reuse it without duplicating the
     * available/reserved bookkeeping {@link #reserve} already got right.
     */
    private void applyReservation(Inventory inventory, String orderId, int quantity) {

        inventory.setAvailableQuantity(inventory.getAvailableQuantity() - quantity);
        inventory.setReservedQuantity(inventory.getReservedQuantity() + quantity);
        inventoryRepository.save(inventory);

        Reservation reservation = new Reservation();
        reservation.setOrderId(orderId);
        reservation.setProductId(inventory.getProductId());
        reservation.setWarehouseId(inventory.getWarehouseId());
        reservation.setQuantity(quantity);
        reservation.setStatus(ReservationStatus.RESERVED);

        // saveAndFlush, not save: forces the @Version check and the unique-constraint check
        // to happen here rather than at commit, so the caller's retry loop sees a failure it
        // can actually act on instead of an exception thrown after the method returned.
        reservationRepository.saveAndFlush(reservation);
    }

    /**
     * Phase D7: search the requested region's warehouse first, then every other warehouse
     * in registration order, greedily taking whatever is available from each until the
     * requested quantity is met or warehouses run out. Unlike {@link #reserveOrder}, this
     * never fails a line for being short -- "never reject" means the caller (order-service)
     * decides what a shortfall means (a backorder), not this method.
     *
     * <p>Idempotent the same way {@link #reserve} is: if this order already holds a
     * reservation for this sku's productId in a candidate warehouse, that warehouse is
     * skipped (already accounted for by an earlier delivery), not double-reserved.
     */
    @Transactional
    public FulfillmentResponse fulfillSalesOrderLine(FulfillmentRequest request) {

        Product product = productRepository.findBySku(request.getSkuNumber())
                .orElseThrow(() -> new ProductNotFoundException(
                        "No inventory-service product registered for sku=" + request.getSkuNumber()));

        CatalogItem catalogItem = catalogItemRepository.findBySkuNumber(request.getSkuNumber())
                .orElseThrow(() -> new CatalogItemNotFoundException(request.getSkuNumber()));

        List<Warehouse> ordered = orderedByRegionThenAge(request.getRegion());

        if (ordered.isEmpty()) {
            throw new IllegalArgumentException(
                    "No warehouse is registered at all -- cannot fulfill sku=" + request.getSkuNumber());
        }

        int remaining = request.getQuantity();
        List<FulfillmentResponse.Allocation> allocations = new ArrayList<>();

        for (Warehouse warehouse : ordered) {

            if (remaining <= 0) {
                break;
            }

            boolean alreadyHeld = reservationRepository
                    .findByOrderIdAndProductIdAndWarehouseId(
                            request.getOrderId(), product.getId(), warehouse.getWarehouseId())
                    .isPresent();

            if (alreadyHeld) {
                continue;
            }

            Optional<Inventory> maybeInventory = inventoryRepository
                    .findByProductIdAndWarehouseId(product.getId(), warehouse.getWarehouseId());

            if (maybeInventory.isEmpty() || maybeInventory.get().getAvailableQuantity() <= 0) {
                continue;
            }

            Inventory inventory = maybeInventory.get();
            int take = Math.min(inventory.getAvailableQuantity(), remaining);

            applyReservation(inventory, request.getOrderId(), take);
            allocations.add(new FulfillmentResponse.Allocation(warehouse.getWarehouseId(), take));
            remaining -= take;
        }

        inventoryRepository.flush();
        reservationRepository.flush();

        int shipQuantity = request.getQuantity() - remaining;

        return new FulfillmentResponse(
                product.getId(), catalogItem.getSalePrice(), catalogItem.getUnitWeight(),
                shipQuantity, remaining, allocations, ordered.get(0).getWarehouseId());
    }

    private List<Warehouse> orderedByRegionThenAge(String region) {
        List<Warehouse> ordered = new ArrayList<>(warehouseRepository.findByRegionOrderByCreatedAtAsc(region));
        ordered.addAll(warehouseRepository.findByRegionNotOrderByCreatedAtAsc(region));
        return ordered;
    }

    /**
     * Reserves every line of one order, atomically and exactly once.
     *
     * <p>This is the Phase 4 entry point, and it differs from {@link #reserve} in three ways
     * that matter:
     *
     * <ol>
     *   <li><strong>Idempotent by event.</strong> The {@code processed_event} row is written
     *       in this same transaction, so the record of having handled the event and the
     *       effects of handling it commit or roll back together. A redelivery finds the row
     *       and does nothing.</li>
     *   <li><strong>All-or-nothing.</strong> Every line is checked before any line is
     *       applied. If one line is short, the method returns FAILED having mutated no
     *       stock at all — so there is nothing to compensate for. The previous
     *       reserve-then-release-on-failure dance is gone, along with the window where
     *       stock sat reserved for an order that was about to be rejected.</li>
     *   <li><strong>One transaction.</strong> The caller retries the whole thing on an
     *       optimistic-lock clash, so a losing attempt re-reads every row rather than
     *       retrying one line against a stale snapshot of the others.</li>
     * </ol>
     *
     * <p>Note the belt and braces: even if the {@code eventId} changes between deliveries —
     * which it would if the publisher regenerated it — the unique constraint on
     * (orderId, productId, warehouseId) still prevents double reservation. Event-level
     * idempotency is convenient; the business constraint is the one that cannot be fooled.
     */
    @Transactional
    public ReserveOutcome reserveOrder(String eventId, String orderId, List<ReservationLine> lines) {

        if (processedEventRepository.existsById(eventId)) {
            return ReserveOutcome.alreadyProcessed();
        }

        // Written first so the primary key rejects a concurrent duplicate immediately, but
        // committed only with everything below it.
        processedEventRepository.save(new ProcessedEvent(eventId, "OrderPlaced"));

        // Phase 1 -- look everything up and check it. Nothing is mutated here, so returning
        // early leaves stock exactly as it was.
        List<Inventory> loaded = new ArrayList<>(lines.size());

        for (ReservationLine line : lines) {

            Inventory inventory;
            try {
                inventory = loadInventory(line.productId(), line.warehouseId());
            } catch (InventoryNotFoundException missing) {
                return ReserveOutcome.failed(missing.getMessage());
            }

            if (inventory.getAvailableQuantity() < line.quantity()) {
                return ReserveOutcome.failed(
                        "Insufficient inventory for productId=" + line.productId()
                                + ", warehouseId=" + line.warehouseId()
                                + ". Available=" + inventory.getAvailableQuantity()
                                + ", requested=" + line.quantity());
            }

            loaded.add(inventory);
        }

        // Phase 2 -- apply. Every line is known to be satisfiable at this point.
        for (int i = 0; i < lines.size(); i++) {

            ReservationLine line = lines.get(i);
            Inventory inventory = loaded.get(i);

            boolean alreadyHeld = reservationRepository
                    .findByOrderIdAndProductIdAndWarehouseId(
                            orderId, line.productId(), line.warehouseId())
                    .isPresent();

            if (alreadyHeld) {
                // Same order, different eventId. The constraint would reject the insert;
                // skipping keeps the operation idempotent instead of failing the batch.
                continue;
            }

            inventory.setAvailableQuantity(inventory.getAvailableQuantity() - line.quantity());
            inventory.setReservedQuantity(inventory.getReservedQuantity() + line.quantity());
            inventoryRepository.save(inventory);

            Reservation reservation = new Reservation();
            reservation.setOrderId(orderId);
            reservation.setProductId(line.productId());
            reservation.setWarehouseId(line.warehouseId());
            reservation.setQuantity(line.quantity());
            reservation.setStatus(ReservationStatus.RESERVED);
            reservationRepository.save(reservation);
        }

        // Forces the @Version check and the unique constraint to fire here, inside the
        // caller's retry, rather than at commit where the retry can no longer help.
        inventoryRepository.flush();
        reservationRepository.flush();

        return ReserveOutcome.reserved();
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
