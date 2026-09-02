package com.demo.order_service.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Each service keeps its own handler rather than sharing one through a common library.
 * A shared "commons" jar is the quickest way to couple independently deployable services
 * back together, and it is a coupling interviewers ask about.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleOrderNotFound(
            OrderNotFoundException exception
    ) {
        return body(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", exception.getMessage());
    }

    /**
     * 409, not 400: the request was well-formed, it just asked for something the order's
     * current state does not permit.
     */
    @ExceptionHandler(InvalidOrderStateTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidTransition(
            InvalidOrderStateTransitionException exception
    ) {
        return body(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", exception.getMessage());
    }

    /**
     * Turns {@code @Valid} failures into a 400 naming each offending field. Nested paths
     * come through intact, so a bad line item reports as {@code items[0].quantity} rather
     * than a vague complaint about the request body.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException exception
    ) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("error", "VALIDATION_FAILED");
        response.put("message", "Request validation failed");
        response.put("fieldErrors", fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(VendorSkuNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleVendorSkuNotFound(
            VendorSkuNotFoundException exception
    ) {
        return body(HttpStatus.NOT_FOUND, "VENDOR_SKU_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(PurchaseOrderNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlePurchaseOrderNotFound(
            PurchaseOrderNotFoundException exception
    ) {
        return body(HttpStatus.NOT_FOUND, "PURCHASE_ORDER_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException exception
    ) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    private ResponseEntity<Map<String, Object>> body(
            HttpStatus status,
            String code,
            String message
    ) {

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("error", code);
        response.put("message", message);

        return ResponseEntity.status(status).body(response);
    }
}
