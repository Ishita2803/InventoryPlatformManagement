package com.demo.order_service.service;

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
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase D9's whole point: a direct order never touches inventory-service's reservation
 * machinery at all, unlike a Phase D7 sales order.
 */
@ExtendWith(MockitoExtension.class)
class DirectOrderServiceTest {

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

    private DirectOrderService directOrderService;

    @BeforeEach
    void setUp() {
        directOrderService = new DirectOrderService(
                inventoryServiceClient, purchaseOrderService, orderRepository, new OrderMapper(),
                paymentClient, outboxWriter);
    }

    @Test
    @DisplayName("isDirectOrder: only the explicit direct flag decides, not the presence of a skuNumber")
    void identifiesADirectOrderByItsFlag() {

        CreateOrderRequest direct = request("SKU-1", 2);
        assertThat(DirectOrderService.isDirectOrder(direct)).isTrue();

        CreateOrderRequest notDirect = request("SKU-1", 2);
        notDirect.setDirect(false);
        assertThat(DirectOrderService.isDirectOrder(notDirect)).isFalse();

        CreateOrderRequest unset = request("SKU-1", 2);
        unset.setDirect(null);
        assertThat(DirectOrderService.isDirectOrder(unset)).isFalse();
    }

    @Test
    @DisplayName("never calls inventory-service's fulfillment or release endpoints")
    void neverTouchesInventoryService() {

        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryServiceClient.getCatalogItem("SKU-1"))
                .thenReturn(new InventoryServiceClient.CatalogItem(
                        "SKU-1", new BigDecimal("1.500"), new BigDecimal("70.00")));

        directOrderService.create(request("SKU-1", 3));

        verify(inventoryServiceClient, never()).fulfill(any(), any(), any(), any());
        verify(inventoryServiceClient, never()).release(any());
    }

    @Test
    @DisplayName("places a DIRECT purchase order with no warehouse, and prices at Impulse's own sale price")
    void placesADirectPurchaseOrderAndPricesAtSalePrice() {

        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(inventoryServiceClient.getCatalogItem("SKU-1"))
                .thenReturn(new InventoryServiceClient.CatalogItem(
                        "SKU-1", new BigDecimal("1.500"), new BigDecimal("70.00")));
        when(paymentClient.generateInvoice(any(), any(), any()))
                .thenReturn(new PaymentClient.InvoiceResult("inv-1", new BigDecimal("210.00")));

        OrderResponse response = directOrderService.create(request("SKU-1", 3));

        assertThat(response.status()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        assertThat(response.direct()).isTrue();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().warehouseId()).isNull();
        assertThat(response.items().getFirst().unitPrice()).isEqualByComparingTo("70.00");
        assertThat(response.totalAmount()).isEqualByComparingTo("210.00");

        ArgumentCaptor<CreatePurchaseOrderRequest> captor = ArgumentCaptor.forClass(CreatePurchaseOrderRequest.class);
        verify(purchaseOrderService).create(captor.capture(), eq(PurchaseOrderPurpose.DIRECT));
        assertThat(captor.getValue().getWarehouseId()).isNull();
        assertThat(captor.getValue().getQuantity()).isEqualTo(3);

        verify(outboxWriter).writeInvoiceGenerated(any(), eq("CUST-1"), eq("inv-1"), eq(new BigDecimal("210.00")));
    }

    @Test
    @DisplayName("missing carrierCode is rejected before any vendor lookup is made")
    void missingCarrierCodeIsRejected() {

        CreateOrderRequest request = request("SKU-1", 2);
        request.setCarrierCode(null);

        assertThatThrownBy(() -> directOrderService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carrierCode");

        verify(inventoryServiceClient, never()).getCatalogItem(any());
    }

    private CreateOrderRequest request(String sku, int quantity) {

        CreateOrderRequest request = new CreateOrderRequest();
        request.setCustomerId("CUST-1");
        request.setCarrierCode("BLUEDART");
        request.setDirect(true);

        OrderItemRequest item = new OrderItemRequest();
        item.setSkuNumber(sku);
        item.setQuantity(quantity);

        request.setItems(List.of(item));
        return request;
    }
}
