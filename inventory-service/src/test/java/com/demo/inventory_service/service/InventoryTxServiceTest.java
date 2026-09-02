package com.demo.inventory_service.service;

import com.demo.inventory_service.dto.FulfillmentRequest;
import com.demo.inventory_service.dto.FulfillmentResponse;
import com.demo.inventory_service.dto.InventoryRequest;
import com.demo.inventory_service.dto.InventoryResponse;
import com.demo.inventory_service.dto.ProductRequest;
import com.demo.inventory_service.dto.ReserveInventoryRequest;
import com.demo.inventory_service.exception.CatalogItemNotFoundException;
import com.demo.inventory_service.exception.DuplicateSkuException;
import com.demo.inventory_service.exception.InsufficientInventoryException;
import com.demo.inventory_service.exception.InventoryNotFoundException;
import com.demo.inventory_service.exception.ProductNotFoundException;
import com.demo.inventory_service.models.CatalogItem;
import com.demo.inventory_service.models.Inventory;
import com.demo.inventory_service.models.Product;
import com.demo.inventory_service.models.Reservation;
import com.demo.inventory_service.models.ReservationStatus;
import com.demo.inventory_service.models.Warehouse;
import com.demo.inventory_service.repository.CatalogItemRepository;
import com.demo.inventory_service.repository.InventoryRepository;
import com.demo.inventory_service.repository.ProcessedEventRepository;
import com.demo.inventory_service.repository.ProductRepository;
import com.demo.inventory_service.repository.ReservationRepository;
import com.demo.inventory_service.repository.WarehouseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Business rules of the reservation lifecycle, isolated from the database.
 *
 * <p>What these tests can and cannot prove matters: they pin down the decision logic, but
 * they cannot demonstrate that concurrent reservations fail to oversell, because a mock
 * has no {@code @Version} column. That claim is proved separately, against a real database,
 * in {@code InventoryConcurrencyTest}.
 */
@ExtendWith(MockitoExtension.class)
class InventoryTxServiceTest {

    private static final String ORDER_ID = "11111111-1111-1111-1111-111111111111";
    private static final Long PRODUCT_ID = 1L;
    private static final String WAREHOUSE_ID = "WH-1";

    @Mock
    private ProductRepository productRepository;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private CatalogItemRepository catalogItemRepository;

    @Mock
    private WarehouseRepository warehouseRepository;

    @InjectMocks
    private InventoryTxService txService;

    @Test
    @DisplayName("sufficient stock: reservation succeeds and stock moves available -> reserved")
    void reserveWithSufficientStockMovesStock() {

        Inventory inventory = inventory(10, 0);

        when(reservationRepository.findByOrderIdAndProductIdAndWarehouseId(
                ORDER_ID, PRODUCT_ID, WAREHOUSE_ID)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdAndWarehouseId(PRODUCT_ID, WAREHOUSE_ID))
                .thenReturn(Optional.of(inventory));

        InventoryResponse response = txService.reserve(reserveRequest(4));

        assertThat(response.getAvailableQuantity()).isEqualTo(6);
        assertThat(response.getReservedQuantity()).isEqualTo(4);

        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).saveAndFlush(captor.capture());

        Reservation saved = captor.getValue();
        assertThat(saved.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(saved.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(saved.getWarehouseId()).isEqualTo(WAREHOUSE_ID);
        assertThat(saved.getQuantity()).isEqualTo(4);
        assertThat(saved.getStatus()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("insufficient stock: reservation is rejected and nothing is mutated")
    void reserveWithInsufficientStockIsRejected() {

        Inventory inventory = inventory(3, 0);

        when(reservationRepository.findByOrderIdAndProductIdAndWarehouseId(
                ORDER_ID, PRODUCT_ID, WAREHOUSE_ID)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdAndWarehouseId(PRODUCT_ID, WAREHOUSE_ID))
                .thenReturn(Optional.of(inventory));

        assertThatThrownBy(() -> txService.reserve(reserveRequest(5)))
                .isInstanceOf(InsufficientInventoryException.class)
                .hasMessageContaining("Available=3")
                .hasMessageContaining("requested=5");

        assertThat(inventory.getAvailableQuantity()).isEqualTo(3);
        assertThat(inventory.getReservedQuantity()).isZero();
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("redelivery: a second reserve for the same orderId does not reserve twice")
    void reserveIsIdempotentForTheSameOrder() {

        Reservation alreadyHeld = new Reservation();
        alreadyHeld.setOrderId(ORDER_ID);
        alreadyHeld.setProductId(PRODUCT_ID);
        alreadyHeld.setWarehouseId(WAREHOUSE_ID);
        alreadyHeld.setQuantity(4);
        alreadyHeld.setStatus(ReservationStatus.RESERVED);

        when(reservationRepository.findByOrderIdAndProductIdAndWarehouseId(
                ORDER_ID, PRODUCT_ID, WAREHOUSE_ID)).thenReturn(Optional.of(alreadyHeld));
        when(inventoryRepository.findByProductIdAndWarehouseId(PRODUCT_ID, WAREHOUSE_ID))
                .thenReturn(Optional.of(inventory(6, 4)));

        InventoryResponse response = txService.reserve(reserveRequest(4));

        // Reports current state, but changes nothing.
        assertThat(response.getAvailableQuantity()).isEqualTo(6);
        assertThat(response.getReservedQuantity()).isEqualTo(4);
        verify(inventoryRepository, never()).save(any());
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("unknown inventory: reserve fails cleanly with InventoryNotFound")
    void reserveUnknownInventoryFailsCleanly() {

        when(reservationRepository.findByOrderIdAndProductIdAndWarehouseId(
                ORDER_ID, PRODUCT_ID, WAREHOUSE_ID)).thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdAndWarehouseId(PRODUCT_ID, WAREHOUSE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> txService.reserve(reserveRequest(1)))
                .isInstanceOf(InventoryNotFoundException.class)
                .hasMessageContaining("productId=" + PRODUCT_ID);
    }

    @Test
    @DisplayName("release restores stock and marks the reservation RELEASED")
    void releaseRestoresStock() {

        Reservation held = reservation(4, ReservationStatus.RESERVED);
        Inventory inventory = inventory(6, 4);

        when(reservationRepository.findByOrderIdAndStatus(ORDER_ID, ReservationStatus.RESERVED))
                .thenReturn(List.of(held));
        when(inventoryRepository.findByProductIdAndWarehouseId(PRODUCT_ID, WAREHOUSE_ID))
                .thenReturn(Optional.of(inventory));

        List<InventoryResponse> responses = txService.releaseByOrderId(ORDER_ID);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().getAvailableQuantity()).isEqualTo(10);
        assertThat(responses.getFirst().getReservedQuantity()).isZero();
        assertThat(held.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    @DisplayName("releasing an order that holds nothing is a no-op, not a 404")
    void releaseUnknownOrderIsNoOp() {

        when(reservationRepository.findByOrderIdAndStatus(ORDER_ID, ReservationStatus.RESERVED))
                .thenReturn(List.of());

        assertThat(txService.releaseByOrderId(ORDER_ID)).isEmpty();
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("confirm removes reserved stock without returning it to available")
    void confirmDoesNotRestoreAvailable() {

        Reservation held = reservation(4, ReservationStatus.RESERVED);
        Inventory inventory = inventory(6, 4);

        when(reservationRepository.findByOrderIdAndStatus(ORDER_ID, ReservationStatus.RESERVED))
                .thenReturn(List.of(held));
        when(inventoryRepository.findByProductIdAndWarehouseId(PRODUCT_ID, WAREHOUSE_ID))
                .thenReturn(Optional.of(inventory));

        List<InventoryResponse> responses = txService.confirmByOrderId(ORDER_ID);

        assertThat(responses).hasSize(1);
        // The stock shipped: it leaves reserved and does NOT come back to available.
        assertThat(responses.getFirst().getAvailableQuantity()).isEqualTo(6);
        assertThat(responses.getFirst().getReservedQuantity()).isZero();
        assertThat(held.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("adding inventory for an unknown product fails with ProductNotFound")
    void addInventoryUnknownProductFailsCleanly() {

        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

        InventoryRequest request = new InventoryRequest();
        request.setProductId(PRODUCT_ID);
        request.setWarehouseId(WAREHOUSE_ID);
        request.setQuantity(5);

        assertThatThrownBy(() -> txService.addInventory(request))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("productId=" + PRODUCT_ID);
    }

    @Test
    @DisplayName("creating a product with an existing SKU fails with DuplicateSku")
    void createProductWithDuplicateSkuFailsCleanly() {

        when(productRepository.findBySku("SKU-1")).thenReturn(Optional.of(new Product()));

        ProductRequest request = new ProductRequest();
        request.setSku("SKU-1");
        request.setName("Widget");

        assertThatThrownBy(() -> txService.createProduct(request))
                .isInstanceOf(DuplicateSkuException.class)
                .hasMessageContaining("SKU-1");
    }

    @Test
    @DisplayName("fulfillment: full stock in the region-matched warehouse ships in full, no shortfall")
    void fulfillmentShipsInFullFromRegionMatchedWarehouse() {

        Warehouse mumbai = warehouse("WH-MUMBAI", "MUMBAI");

        when(productRepository.findBySku("SKU-1")).thenReturn(Optional.of(product()));
        when(catalogItemRepository.findBySkuNumber("SKU-1")).thenReturn(Optional.of(catalogItem()));
        when(warehouseRepository.findByRegionOrderByCreatedAtAsc("MUMBAI")).thenReturn(List.of(mumbai));
        when(warehouseRepository.findByRegionNotOrderByCreatedAtAsc("MUMBAI")).thenReturn(List.of());
        when(reservationRepository.findByOrderIdAndProductIdAndWarehouseId(ORDER_ID, PRODUCT_ID, "WH-MUMBAI"))
                .thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdAndWarehouseId(PRODUCT_ID, "WH-MUMBAI"))
                .thenReturn(Optional.of(inventory("WH-MUMBAI", 10, 0)));

        FulfillmentResponse response = txService.fulfillSalesOrderLine(fulfillmentRequest("MUMBAI", 4));

        assertThat(response.shipQuantity()).isEqualTo(4);
        assertThat(response.shortfall()).isZero();
        assertThat(response.allocations()).hasSize(1);
        assertThat(response.allocations().getFirst().warehouseId()).isEqualTo("WH-MUMBAI");
        assertThat(response.allocations().getFirst().quantity()).isEqualTo(4);
        assertThat(response.backorderWarehouseId()).isEqualTo("WH-MUMBAI");
    }

    @Test
    @DisplayName("fulfillment: region warehouse short, fallback warehouse covers the rest, no shortfall")
    void fulfillmentFallsBackToASecondWarehouse() {

        Warehouse mumbai = warehouse("WH-MUMBAI", "MUMBAI");
        Warehouse pune = warehouse("WH-PUNE", "PUNE");

        when(productRepository.findBySku("SKU-1")).thenReturn(Optional.of(product()));
        when(catalogItemRepository.findBySkuNumber("SKU-1")).thenReturn(Optional.of(catalogItem()));
        when(warehouseRepository.findByRegionOrderByCreatedAtAsc("MUMBAI")).thenReturn(List.of(mumbai));
        when(warehouseRepository.findByRegionNotOrderByCreatedAtAsc("MUMBAI")).thenReturn(List.of(pune));
        when(reservationRepository.findByOrderIdAndProductIdAndWarehouseId(ORDER_ID, PRODUCT_ID, "WH-MUMBAI"))
                .thenReturn(Optional.empty());
        when(reservationRepository.findByOrderIdAndProductIdAndWarehouseId(ORDER_ID, PRODUCT_ID, "WH-PUNE"))
                .thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdAndWarehouseId(PRODUCT_ID, "WH-MUMBAI"))
                .thenReturn(Optional.of(inventory("WH-MUMBAI", 3, 0)));
        when(inventoryRepository.findByProductIdAndWarehouseId(PRODUCT_ID, "WH-PUNE"))
                .thenReturn(Optional.of(inventory("WH-PUNE", 10, 0)));

        FulfillmentResponse response = txService.fulfillSalesOrderLine(fulfillmentRequest("MUMBAI", 5));

        assertThat(response.shipQuantity()).isEqualTo(5);
        assertThat(response.shortfall()).isZero();
        assertThat(response.allocations()).hasSize(2);
        assertThat(response.allocations().get(0).warehouseId()).isEqualTo("WH-MUMBAI");
        assertThat(response.allocations().get(0).quantity()).isEqualTo(3);
        assertThat(response.allocations().get(1).warehouseId()).isEqualTo("WH-PUNE");
        assertThat(response.allocations().get(1).quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("fulfillment: no warehouse has enough stock -- ships what it can, reports the shortfall, never throws")
    void fulfillmentReportsShortfallInsteadOfRejecting() {

        Warehouse mumbai = warehouse("WH-MUMBAI", "MUMBAI");

        when(productRepository.findBySku("SKU-1")).thenReturn(Optional.of(product()));
        when(catalogItemRepository.findBySkuNumber("SKU-1")).thenReturn(Optional.of(catalogItem()));
        when(warehouseRepository.findByRegionOrderByCreatedAtAsc("MUMBAI")).thenReturn(List.of(mumbai));
        when(warehouseRepository.findByRegionNotOrderByCreatedAtAsc("MUMBAI")).thenReturn(List.of());
        when(reservationRepository.findByOrderIdAndProductIdAndWarehouseId(ORDER_ID, PRODUCT_ID, "WH-MUMBAI"))
                .thenReturn(Optional.empty());
        when(inventoryRepository.findByProductIdAndWarehouseId(PRODUCT_ID, "WH-MUMBAI"))
                .thenReturn(Optional.of(inventory("WH-MUMBAI", 2, 0)));

        FulfillmentResponse response = txService.fulfillSalesOrderLine(fulfillmentRequest("MUMBAI", 5));

        assertThat(response.shipQuantity()).isEqualTo(2);
        assertThat(response.shortfall()).isEqualTo(3);
        assertThat(response.allocations()).hasSize(1);
        assertThat(response.backorderWarehouseId()).isEqualTo("WH-MUMBAI");
    }

    @Test
    @DisplayName("fulfillment: no warehouse registered at all fails cleanly, distinct from a mere shortfall")
    void fulfillmentWithNoWarehousesFailsCleanly() {

        when(productRepository.findBySku("SKU-1")).thenReturn(Optional.of(product()));
        when(catalogItemRepository.findBySkuNumber("SKU-1")).thenReturn(Optional.of(catalogItem()));
        when(warehouseRepository.findByRegionOrderByCreatedAtAsc("MUMBAI")).thenReturn(List.of());
        when(warehouseRepository.findByRegionNotOrderByCreatedAtAsc("MUMBAI")).thenReturn(List.of());

        assertThatThrownBy(() -> txService.fulfillSalesOrderLine(fulfillmentRequest("MUMBAI", 5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fulfillment: unknown sku fails with ProductNotFound, same as the D6 fulfillment listener would hit")
    void fulfillmentUnknownSkuFailsCleanly() {

        when(productRepository.findBySku("SKU-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> txService.fulfillSalesOrderLine(fulfillmentRequest("MUMBAI", 5)))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    @DisplayName("fulfillment: sku with no catalog price fails with CatalogItemNotFound")
    void fulfillmentUnknownCatalogItemFailsCleanly() {

        when(productRepository.findBySku("SKU-1")).thenReturn(Optional.of(product()));
        when(catalogItemRepository.findBySkuNumber("SKU-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> txService.fulfillSalesOrderLine(fulfillmentRequest("MUMBAI", 5)))
                .isInstanceOf(CatalogItemNotFoundException.class);
    }

    private Warehouse warehouse(String warehouseId, String region) {
        return new Warehouse(warehouseId, "Some location", region);
    }

    private Product product() {
        Product product = new Product();
        product.setId(PRODUCT_ID);
        product.setSku("SKU-1");
        product.setName("Widget");
        return product;
    }

    private CatalogItem catalogItem() {
        return new CatalogItem("SKU-1", "VENDOR-1",
                new java.math.BigDecimal("1.500"), new java.math.BigDecimal("70.00"));
    }

    private FulfillmentRequest fulfillmentRequest(String region, int quantity) {
        FulfillmentRequest request = new FulfillmentRequest();
        request.setSkuNumber("SKU-1");
        request.setRegion(region);
        request.setQuantity(quantity);
        request.setOrderId(ORDER_ID);
        return request;
    }

    private Inventory inventory(int available, int reserved) {
        return inventory(WAREHOUSE_ID, available, reserved);
    }

    private Inventory inventory(String warehouseId, int available, int reserved) {
        Inventory inventory = new Inventory();
        inventory.setId(1L);
        inventory.setProductId(PRODUCT_ID);
        inventory.setWarehouseId(warehouseId);
        inventory.setAvailableQuantity(available);
        inventory.setReservedQuantity(reserved);
        return inventory;
    }

    private Reservation reservation(int quantity, ReservationStatus status) {
        Reservation reservation = new Reservation();
        reservation.setOrderId(ORDER_ID);
        reservation.setProductId(PRODUCT_ID);
        reservation.setWarehouseId(WAREHOUSE_ID);
        reservation.setQuantity(quantity);
        reservation.setStatus(status);
        return reservation;
    }

    private ReserveInventoryRequest reserveRequest(int quantity) {
        ReserveInventoryRequest request = new ReserveInventoryRequest();
        request.setOrderId(ORDER_ID);
        request.setProductId(PRODUCT_ID);
        request.setWarehouseId(WAREHOUSE_ID);
        request.setQuantity(quantity);
        return request;
    }
}
