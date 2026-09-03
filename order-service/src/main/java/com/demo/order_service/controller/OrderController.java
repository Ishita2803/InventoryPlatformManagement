package com.demo.order_service.controller;

import com.demo.order_service.dto.CreateOrderRequest;
import com.demo.order_service.dto.OrderResponse;
import com.demo.order_service.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Validated
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request
    ) {

        OrderResponse response = orderService.createOrder(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String orderId) {
        return ResponseEntity.ok(orderService.getOrder(orderId));
    }

    /**
     * Paged deliberately. An unbounded {@code findAll} is fine with fifty rows in a demo and
     * a way to exhaust heap once the table is real; capping the page size means a client
     * cannot ask for a million rows either.
     */
    @GetMapping
    public ResponseEntity<List<OrderResponse>> listOrders(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {

        return ResponseEntity.ok(
                orderService.listOrders(
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
                )
        );
    }

    /**
     * Phase D10: a carrier's own view of the orders assigned to them. {@code carrierCode}
     * comes only from the gateway-forwarded header (the caller's verified JWT), never a
     * client-supplied query param -- same ownership pattern every other role-scoped
     * endpoint in this project already uses.
     */
    @GetMapping("/assigned")
    public ResponseEntity<List<OrderResponse>> listAssignedOrders(
            @RequestHeader("X-User-Business-Id") String carrierCode,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {

        return ResponseEntity.ok(
                orderService.listOrdersForCarrier(
                        carrierCode, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
                )
        );
    }
}
