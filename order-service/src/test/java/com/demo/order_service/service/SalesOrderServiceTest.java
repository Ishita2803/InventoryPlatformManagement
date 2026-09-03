package com.demo.order_service.service;

import com.demo.order_service.client.CustomerServiceClient;
import com.demo.order_service.client.InventoryServiceClient;
import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.CreatePurchaseOrderRequest;
import com.demo.order_service.dto.OrderItemRequest;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.mapper.OrderMapper;
import com.demo.order_service.models.Order;
import com.demo.order_service.models.OrderStatus;
import com.demo.order_service.models.PurchaseOrderPurpose;
import com.demo.order_service.outbox.OutboxWriter;
import com.demo.order_service.payment.PaymentClient;
import com.demo.order_service.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase D7's decision logic: when a shortfall triggers a backorder, when it doesn't, and
 * why a failed persist has to release whatever inventory-service already reserved.
 */
@ExtendWith(MockitoExtension.class)
class SalesOrderServiceTest {

    @Mock
    private InventoryServiceClient inventoryServiceClient;

    @Mock
    private PurchaseOrderService purchaseOrderService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentClient paymentClient;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private CustomerServiceClient customerServiceClient;

    private SalesOrderService salesOrderService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        salesOrderService = new SalesOrderService(
                inventoryServiceClient, purchaseOrderService, orderRepository, new OrderMapper(),
                paymentClient, outboxWriter, customerServiceClient);
    }

    @Test
    @DisplayName("isSalesOrder: an item carrying a skuNumber makes the whole request a sales order")
    void identifiesASalesOrderByItsItems() {

        CreateOrderRequest request = requestWithOneItem("SKU-1", 4, "MUMBAI");
        assertThat(SalesOrderService.isSalesOrder(request)).isTrue();

        CreateOrderRequest legacy = new CreateOrderRequest();
        OrderItemRequest legacyItem = new OrderItemRequest();
        legacyItem.setProductId(1L);
        legacyItem.setWarehouseId("WH-1");
        legacyItem.setQuantity(1);
        legacyItem.setUnitPrice(BigDecimal.TEN);
        legacy.setItems(List.of(legacyItem));
        assertThat(SalesOrderService.isSalesOrder(legacy)).isFalse();
    }

    @Test
    @DisplayName("full stock: ships in full, no backorder placed")
    void fullStockShipsInFullWithNoBackorder() {

        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryServiceClient.fulfill(eq("SKU-1"), eq("MUMBAI"), eq(4), anyString()))
                .thenReturn(new InventoryServiceClient.FulfillmentResult(
                        1L, new BigDecimal("70.00"), new BigDecimal("1.500"),
                        4, 0,
                        List.of(new InventoryServiceClient.FulfillmentResult.Allocation("WH-MUMBAI", 4)),
                        "WH-MUMBAI"));

        OrderResponse response = salesOrderService.create(requestWithOneItem("SKU-1", 4, "MUMBAI"));

        assertThat(response.status()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().warehouseId()).isEqualTo("WH-MUMBAI");
        assertThat(response.items().getFirst().quantity()).isEqualTo(4);
        assertThat(response.totalAmount()).isEqualByComparingTo("280.00");

        verify(purchaseOrderService, never()).create(any(), any());
    }

    @Test
    @DisplayName("partial stock: ships what's available, backorders the shortfall, total only counts what shipped")
    void partialStockBackordersTheShortfall() {

        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryServiceClient.fulfill(eq("SKU-1"), eq("MUMBAI"), eq(10), anyString()))
                .thenReturn(new InventoryServiceClient.FulfillmentResult(
                        1L, new BigDecimal("70.00"), new BigDecimal("1.500"),
                        3, 7,
                        List.of(new InventoryServiceClient.FulfillmentResult.Allocation("WH-MUMBAI", 3)),
                        "WH-MUMBAI"));

        OrderResponse response = salesOrderService.create(requestWithOneItem("SKU-1", 10, "MUMBAI"));

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).warehouseId()).isEqualTo("WH-MUMBAI");
        assertThat(response.items().get(0).quantity()).isEqualTo(3);
        assertThat(response.items().get(1).warehouseId()).isNull();
        assertThat(response.items().get(1).quantity()).isEqualTo(7);
        assertThat(response.totalAmount()).isEqualByComparingTo("210.00");

        ArgumentCaptor<CreatePurchaseOrderRequest> captor = ArgumentCaptor.forClass(CreatePurchaseOrderRequest.class);
        verify(purchaseOrderService).create(captor.capture(), eq(PurchaseOrderPurpose.BACKORDER));
        assertThat(captor.getValue().getSkuNumber()).isEqualTo("SKU-1");
        assertThat(captor.getValue().getQuantity()).isEqualTo(7);
        assertThat(captor.getValue().getWarehouseId()).isEqualTo("WH-MUMBAI");
    }

    @Test
    @DisplayName("zero stock anywhere: never rejects -- the whole line is backordered")
    void zeroStockBackordersTheWholeLine() {

        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryServiceClient.fulfill(eq("SKU-1"), eq("MUMBAI"), eq(5), anyString()))
                .thenReturn(new InventoryServiceClient.FulfillmentResult(
                        1L, new BigDecimal("70.00"), new BigDecimal("1.500"),
                        0, 5, List.of(), "WH-MUMBAI"));

        OrderResponse response = salesOrderService.create(requestWithOneItem("SKU-1", 5, "MUMBAI"));

        assertThat(response.status()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().warehouseId()).isNull();
        assertThat(response.totalAmount()).isEqualByComparingTo("0.00");
        verify(purchaseOrderService).create(any(), eq(PurchaseOrderPurpose.BACKORDER));
    }

    @Test
    @DisplayName("mixing a sku-based item with a legacy productId/warehouseId item is rejected outright")
    void mixedItemsAreRejected() {

        CreateOrderRequest request = new CreateOrderRequest();
        request.setCustomerId("CUST-1");
        request.setDeliveryRegion("MUMBAI");
        request.setCarrierCode("BLUEDART");

        OrderItemRequest skuItem = new OrderItemRequest();
        skuItem.setSkuNumber("SKU-1");
        skuItem.setQuantity(1);

        OrderItemRequest legacyItem = new OrderItemRequest();
        legacyItem.setProductId(1L);
        legacyItem.setWarehouseId("WH-1");
        legacyItem.setQuantity(1);
        legacyItem.setUnitPrice(BigDecimal.TEN);

        request.setItems(List.of(skuItem, legacyItem));

        assertThatThrownBy(() -> salesOrderService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot mix");

        verify(inventoryServiceClient, never()).fulfill(any(), any(), any(), any());
    }

    @Test
    @DisplayName("missing deliveryRegion is rejected before any inventory call is made")
    void missingDeliveryRegionIsRejected() {

        CreateOrderRequest request = requestWithOneItem("SKU-1", 4, null);
        request.setDeliveryRegion(null);

        assertThatThrownBy(() -> salesOrderService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deliveryRegion");

        verify(inventoryServiceClient, never()).fulfill(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a failure after inventory is reserved releases the reservation instead of leaving it stranded")
    void failureAfterReservationTriggersRelease() {

        when(inventoryServiceClient.fulfill(eq("SKU-1"), eq("MUMBAI"), eq(4), anyString()))
                .thenReturn(new InventoryServiceClient.FulfillmentResult(
                        1L, new BigDecimal("70.00"), new BigDecimal("1.500"),
                        4, 0,
                        List.of(new InventoryServiceClient.FulfillmentResult.Allocation("WH-MUMBAI", 4)),
                        "WH-MUMBAI"));
        when(orderRepository.save(any(Order.class))).thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> salesOrderService.create(requestWithOneItem("SKU-1", 4, "MUMBAI")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB down");

        verify(inventoryServiceClient, times(1)).release(anyString());
    }

    @Test
    @DisplayName("anything shipped: an invoice is requested and, on success, queued to the outbox")
    void shippedOrderGeneratesAnInvoice() {

        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryServiceClient.fulfill(eq("SKU-1"), eq("MUMBAI"), eq(4), anyString()))
                .thenReturn(new InventoryServiceClient.FulfillmentResult(
                        1L, new BigDecimal("70.00"), new BigDecimal("1.500"),
                        4, 0,
                        List.of(new InventoryServiceClient.FulfillmentResult.Allocation("WH-MUMBAI", 4)),
                        "WH-MUMBAI"));
        when(paymentClient.generateInvoice(anyString(), eq("BLUEDART"), any()))
                .thenReturn(new PaymentClient.InvoiceResult(
                        "inv-1", new BigDecimal("280.00"), new BigDecimal("15.00"), new BigDecimal("295.00")));
        when(customerServiceClient.getEmail("CUST-1")).thenReturn("cust1@example.com");

        salesOrderService.create(requestWithOneItem("SKU-1", 4, "MUMBAI"));

        ArgumentCaptor<List<PaymentClient.InvoiceLine>> linesCaptor = ArgumentCaptor.forClass(List.class);
        verify(paymentClient).generateInvoice(anyString(), eq("BLUEDART"), linesCaptor.capture());
        assertThat(linesCaptor.getValue()).hasSize(1);
        assertThat(linesCaptor.getValue().getFirst().skuNumber()).isEqualTo("SKU-1");
        assertThat(linesCaptor.getValue().getFirst().quantity()).isEqualTo(4);

        verify(outboxWriter).writeInvoiceGenerated(
                anyString(), eq("CUST-1"), eq("inv-1"), eq("BLUEDART"), any(),
                eq(new BigDecimal("280.00")), eq(new BigDecimal("15.00")), eq(new BigDecimal("295.00")),
                eq("cust1@example.com"));
    }

    @Test
    @DisplayName("nothing shipped: no invoice is requested for a zero-amount order")
    void whollyBackorderedOrderSkipsInvoicing() {

        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryServiceClient.fulfill(eq("SKU-1"), eq("MUMBAI"), eq(5), anyString()))
                .thenReturn(new InventoryServiceClient.FulfillmentResult(
                        1L, new BigDecimal("70.00"), new BigDecimal("1.500"),
                        0, 5, List.of(), "WH-MUMBAI"));

        salesOrderService.create(requestWithOneItem("SKU-1", 5, "MUMBAI"));

        verify(paymentClient, never()).generateInvoice(any(), any(), any());
        verify(outboxWriter, never()).writeInvoiceGenerated(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("payment-service unavailable for invoicing: the order still stands, nothing is queued")
    void invoiceUnavailableDoesNotFailTheOrder() {

        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryServiceClient.fulfill(eq("SKU-1"), eq("MUMBAI"), eq(4), anyString()))
                .thenReturn(new InventoryServiceClient.FulfillmentResult(
                        1L, new BigDecimal("70.00"), new BigDecimal("1.500"),
                        4, 0,
                        List.of(new InventoryServiceClient.FulfillmentResult.Allocation("WH-MUMBAI", 4)),
                        "WH-MUMBAI"));
        when(paymentClient.generateInvoice(any(), any(), any())).thenReturn(null);

        OrderResponse response = salesOrderService.create(requestWithOneItem("SKU-1", 4, "MUMBAI"));

        assertThat(response.status()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        verify(outboxWriter, never()).writeInvoiceGenerated(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(inventoryServiceClient, never()).release(any());
    }

    @Test
    @DisplayName("missing carrierCode is rejected before any inventory call is made")
    void missingCarrierCodeIsRejected() {

        CreateOrderRequest request = requestWithOneItem("SKU-1", 4, "MUMBAI");
        request.setCarrierCode(null);

        assertThatThrownBy(() -> salesOrderService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carrierCode");

        verify(inventoryServiceClient, never()).fulfill(any(), any(), any(), any());
    }

    private CreateOrderRequest requestWithOneItem(String sku, int quantity, String region) {

        CreateOrderRequest request = new CreateOrderRequest();
        request.setCustomerId("CUST-1");
        request.setDeliveryRegion(region);
        request.setCarrierCode("BLUEDART");

        OrderItemRequest item = new OrderItemRequest();
        item.setSkuNumber(sku);
        item.setQuantity(quantity);

        request.setItems(List.of(item));
        return request;
    }
}
