package com.demo.order_service.service;

import com.demo.order_service.client.CustomerServiceClient;
import com.demo.order_service.client.InventoryServiceClient;
import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.CreatePurchaseOrderRequest;
import com.demo.order_service.dto.OrderItemRequest;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.mapper.OrderMapper;
import com.demo.order_service.models.Order;
import com.demo.order_service.models.OrderItem;
import com.demo.order_service.models.OrderStatus;
import com.demo.order_service.models.PurchaseOrderPurpose;
import com.demo.order_service.outbox.OutboxWriter;
import com.demo.order_service.payment.PaymentClient;
import com.demo.order_service.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Phase D9: a direct order buys straight from the vendor and ships straight to the
 * customer -- {@code inventory-service} is never called at all, which is the entire
 * difference from {@link SalesOrderService}. There is no warehouse search, no partial
 * fulfillment, and no shortfall: the mock vendor (Phase D6's
 * {@code PurchaseOrderPlacedListener}) always fulfills, so every direct-order line always
 * "ships" in full, immediately.
 *
 * <p>Impulse still charges its own catalog sale price, not the vendor's cost price --
 * bypassing the warehouse changes how a sku gets to the customer, not what Impulse
 * decided to charge for it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectOrderService {

    private final InventoryServiceClient inventoryServiceClient;
    private final PurchaseOrderService purchaseOrderService;
    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final PaymentClient paymentClient;
    private final OutboxWriter outboxWriter;
    private final CustomerServiceClient customerServiceClient;

    public static boolean isDirectOrder(CreateOrderRequest request) {
        return Boolean.TRUE.equals(request.getDirect());
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {

        validate(request);

        String orderId = UUID.randomUUID().toString();

        Order order = new Order();
        order.setOrderId(orderId);
        order.setCustomerId(request.getCustomerId());
        order.setCarrierCode(request.getCarrierCode());
        order.setDirect(true);
        order.setStatus(OrderStatus.INVENTORY_RESERVED);

        for (OrderItemRequest itemRequest : request.getItems()) {
            resolveLine(order, itemRequest);
        }

        order.setTotalAmount(order.getItems().stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        Order saved = orderRepository.save(order);

        log.info("Created direct order {} for customer {} with {} item(s), total {}",
                saved.getOrderId(), saved.getCustomerId(), saved.getItems().size(), saved.getTotalAmount());

        generateInvoice(saved);

        return orderMapper.toResponse(saved);
    }

    private void resolveLine(Order order, OrderItemRequest itemRequest) {

        InventoryServiceClient.CatalogItem catalogItem =
                inventoryServiceClient.getCatalogItem(itemRequest.getSkuNumber());

        OrderItem item = new OrderItem();
        item.setSkuNumber(itemRequest.getSkuNumber());
        item.setQuantity(itemRequest.getQuantity());
        item.setUnitPrice(catalogItem.salePrice());
        item.setUnitWeight(catalogItem.unitWeight());
        // Deliberately null, not a sentinel warehouse code: a direct order's line was
        // never going to be in a warehouse in the first place. Unlike a Phase D7
        // sales-order line, that does NOT mean "backordered" here -- Order.direct is
        // what tells any later reader which meaning applies.
        item.setWarehouseId(null);
        order.addItem(item);

        CreatePurchaseOrderRequest directPurchase = new CreatePurchaseOrderRequest();
        directPurchase.setSkuNumber(itemRequest.getSkuNumber());
        directPurchase.setQuantity(itemRequest.getQuantity());
        directPurchase.setWarehouseId(null);
        purchaseOrderService.create(directPurchase, PurchaseOrderPurpose.DIRECT);
    }

    /** Same fail-open reasoning as {@code SalesOrderService}: the order and its vendor
     * purchase have already been placed, so a billing hiccup here logs and moves on
     * rather than failing an order that was otherwise handled correctly. */
    private void generateInvoice(Order order) {

        List<PaymentClient.InvoiceLine> lines = order.getItems().stream()
                .map(item -> new PaymentClient.InvoiceLine(
                        item.getSkuNumber(), item.getQuantity(), item.getUnitPrice(), item.getUnitWeight()))
                .toList();

        PaymentClient.InvoiceResult result = paymentClient.generateInvoice(
                order.getOrderId(), order.getCarrierCode(), lines);

        if (result == null) {
            log.warn("No invoice was generated for direct order {} -- payment-service was unavailable",
                    order.getOrderId());
            return;
        }

        List<com.demo.order_service.events.InvoiceGeneratedEvent.Line> invoiceLines = lines.stream()
                .map(line -> new com.demo.order_service.events.InvoiceGeneratedEvent.Line(
                        line.skuNumber(), line.quantity(), line.unitPrice(),
                        line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()))))
                .toList();

        String recipientEmail = customerServiceClient.getEmail(order.getCustomerId());

        outboxWriter.writeInvoiceGenerated(
                order.getOrderId(), order.getCustomerId(), result.invoiceId(), order.getCarrierCode(),
                invoiceLines, result.lineTotal(), result.weightSurcharge(), result.totalAmount(), recipientEmail);
    }

    private void validate(CreateOrderRequest request) {

        if (request.getCarrierCode() == null || request.getCarrierCode().isBlank()) {
            throw new IllegalArgumentException("carrierCode is required for a direct order");
        }

        boolean missingSku = request.getItems().stream().anyMatch(item -> item.getSkuNumber() == null);
        if (missingSku) {
            throw new IllegalArgumentException("Every item in a direct order must carry a skuNumber");
        }
    }
}
