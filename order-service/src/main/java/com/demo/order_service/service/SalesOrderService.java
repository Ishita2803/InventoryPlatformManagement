package com.demo.order_service.service;

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
 * Phase D7: a sales order resolved <em>synchronously</em>, unlike the legacy demo flow's
 * {@code OrderTxService.create}. That is a deliberate divergence, not an inconsistency --
 * the legacy flow decouples order-acceptance from inventory-availability specifically so a
 * slow or down inventory-service cannot block taking an order. Phase D7's "never reject,
 * return the order with whatever it can currently ship" promise requires the opposite: the
 * caller needs to know the real shipQuantity in the response to this very request, so the
 * fulfillment search has to happen before the response is built, not sometime later via
 * Kafka.
 *
 * <p>Every item in the request must carry a {@code skuNumber} — see {@link #isSalesOrder}.
 * A request mixing a sku-based line with a legacy productId/warehouseId line is rejected,
 * not partially honoured, so a caller never gets a silently-half-processed order.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SalesOrderService {

    private final InventoryServiceClient inventoryServiceClient;
    private final PurchaseOrderService purchaseOrderService;
    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final PaymentClient paymentClient;
    private final OutboxWriter outboxWriter;

    public static boolean isSalesOrder(CreateOrderRequest request) {
        return request.getItems() != null
                && request.getItems().stream().anyMatch(item -> item.getSkuNumber() != null);
    }

    /**
     * Resolves fulfillment for every line, persists the order with whatever shipped (and
     * whatever didn't), and auto-backorders any shortfall — all in one transaction, so an
     * order is never left half-written.
     *
     * <p>The inventory-service calls happen <em>inside</em> this transaction, which means a
     * later failure (e.g. this service's own database rejecting the insert) would otherwise
     * leave stock reserved for an order that was never persisted. The catch block below
     * compensates for exactly that by releasing whatever this order already holds — the
     * same {@code /api/inventory/release} endpoint Saga compensation already uses.
     */
    @Transactional
    public OrderResponse create(CreateOrderRequest request) {

        validate(request);

        String orderId = UUID.randomUUID().toString();

        try {
            Order order = new Order();
            order.setOrderId(orderId);
            order.setCustomerId(request.getCustomerId());
            order.setDeliveryRegion(request.getDeliveryRegion());
            order.setCarrierCode(request.getCarrierCode());
            order.setStatus(OrderStatus.INVENTORY_RESERVED);

            for (OrderItemRequest itemRequest : request.getItems()) {
                resolveLine(order, orderId, request.getDeliveryRegion(), itemRequest);
            }

            order.setTotalAmount(shippedTotal(order));

            Order saved = orderRepository.save(order);

            log.info("Created sales order {} for customer {} with {} item(s), shipped total {}",
                    saved.getOrderId(), saved.getCustomerId(), saved.getItems().size(), saved.getTotalAmount());

            generateInvoiceIfAnythingShipped(saved);

            return orderMapper.toResponse(saved);

        } catch (RuntimeException failure) {
            log.warn("Sales order {} failed after inventory was reserved -- releasing", orderId, failure);
            inventoryServiceClient.release(orderId);
            throw failure;
        }
    }

    private void resolveLine(Order order, String orderId, String region, OrderItemRequest itemRequest) {

        InventoryServiceClient.FulfillmentResult result = inventoryServiceClient.fulfill(
                itemRequest.getSkuNumber(), region, itemRequest.getQuantity(), orderId);

        for (InventoryServiceClient.FulfillmentResult.Allocation allocation : result.allocations()) {

            OrderItem shipped = new OrderItem();
            shipped.setSkuNumber(itemRequest.getSkuNumber());
            shipped.setProductId(result.productId());
            shipped.setWarehouseId(allocation.warehouseId());
            shipped.setQuantity(allocation.quantity());
            shipped.setUnitPrice(result.unitPrice());
            shipped.setUnitWeight(result.unitWeight());
            order.addItem(shipped);
        }

        if (result.shortfall() != null && result.shortfall() > 0) {

            OrderItem backordered = new OrderItem();
            backordered.setSkuNumber(itemRequest.getSkuNumber());
            backordered.setProductId(result.productId());
            backordered.setWarehouseId(null);
            backordered.setQuantity(result.shortfall());
            backordered.setUnitPrice(result.unitPrice());
            order.addItem(backordered);

            CreatePurchaseOrderRequest backorderRequest = new CreatePurchaseOrderRequest();
            backorderRequest.setSkuNumber(itemRequest.getSkuNumber());
            backorderRequest.setQuantity(result.shortfall());
            backorderRequest.setWarehouseId(result.backorderWarehouseId());
            purchaseOrderService.create(backorderRequest, PurchaseOrderPurpose.BACKORDER);

            log.info("Order {} short {} of sku {} -- auto-backordered against warehouse {}",
                    orderId, result.shortfall(), itemRequest.getSkuNumber(), result.backorderWarehouseId());
        }
    }

    /** Only shipped rows count toward what's charged now -- a backordered row hasn't left
     * a warehouse yet, and what happens to its price when it eventually ships is priced
     * fresh, whenever it does. */
    private BigDecimal shippedTotal(Order order) {
        return order.getItems().stream()
                .filter(item -> item.getWarehouseId() != null)
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Phase D8: invoices only the shipped portion of the order -- a backordered line isn't
     * charged for until it ships. Skips the call entirely if nothing shipped at all (a
     * wholly-backordered order), since an invoice for zero is not a real invoice.
     *
     * <p>A failed invoice call does not fail the order: see {@code PaymentClient
     * .generateInvoice}'s fails-open fallback. This method only logs if that happens.
     */
    private void generateInvoiceIfAnythingShipped(Order order) {

        List<PaymentClient.InvoiceLine> shippedLines = order.getItems().stream()
                .filter(item -> item.getWarehouseId() != null)
                .map(item -> new PaymentClient.InvoiceLine(
                        item.getSkuNumber(), item.getQuantity(), item.getUnitPrice(), item.getUnitWeight()))
                .toList();

        if (shippedLines.isEmpty()) {
            log.info("Order {} shipped nothing -- no invoice to generate yet", order.getOrderId());
            return;
        }

        PaymentClient.InvoiceResult result = paymentClient.generateInvoice(
                order.getOrderId(), order.getCarrierCode(), shippedLines);

        if (result == null) {
            log.warn("No invoice was generated for order {} -- payment-service was unavailable", order.getOrderId());
            return;
        }

        outboxWriter.writeInvoiceGenerated(
                order.getOrderId(), order.getCustomerId(), result.invoiceId(), result.totalAmount());
    }

    private void validate(CreateOrderRequest request) {

        if (request.getDeliveryRegion() == null || request.getDeliveryRegion().isBlank()) {
            throw new IllegalArgumentException("deliveryRegion is required for a sales order");
        }

        if (request.getCarrierCode() == null || request.getCarrierCode().isBlank()) {
            throw new IllegalArgumentException("carrierCode is required for a sales order");
        }

        boolean mixed = request.getItems().stream().anyMatch(item -> item.getSkuNumber() == null);
        if (mixed) {
            throw new IllegalArgumentException(
                    "Cannot mix sku-based sales-order items with legacy productId/warehouseId items in one request");
        }
    }
}
