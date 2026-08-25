package com.demo.inventory_service.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(InventoryNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleInventoryNotFound(
            InventoryNotFoundException exception
    ) {
        return body(HttpStatus.NOT_FOUND, "INVENTORY_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleProductNotFound(
            ProductNotFoundException exception
    ) {
        return body(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(InsufficientInventoryException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientInventory(
            InsufficientInventoryException exception
    ) {
        return body(HttpStatus.CONFLICT, "INSUFFICIENT_INVENTORY", exception.getMessage());
    }

    @ExceptionHandler(DuplicateSkuException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateSku(
            DuplicateSkuException exception
    ) {
        return body(HttpStatus.CONFLICT, "DUPLICATE_SKU", exception.getMessage());
    }

    /**
     * The retry budget was exhausted under contention. A 409 tells the caller to try again;
     * a 500 would imply the server is broken, which it is not.
     */
    @ExceptionHandler(ReservationConflictException.class)
    public ResponseEntity<Map<String, Object>> handleReservationConflict(
            ReservationConflictException exception
    ) {
        return body(HttpStatus.CONFLICT, "RESERVATION_CONFLICT", exception.getMessage());
    }

    /**
     * Safety net. {@code InventoryService} retries these, so one reaching here means it
     * escaped a path that is not retry-wrapped -- still a 409 rather than a 500, but worth
     * logging as a warning because it points at a gap.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLock(
            ObjectOptimisticLockingFailureException exception
    ) {
        log.warn("Unretried optimistic-locking failure reached the controller layer", exception);
        return body(
                HttpStatus.CONFLICT,
                "CONCURRENT_MODIFICATION",
                "The record was modified by another request. Please retry."
        );
    }

    /**
     * Turns {@code @Valid} failures into a 400 that names the offending fields, instead of
     * Spring's default blob of binding-result text.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException exception
    ) {

        Map<String, String> fieldErrors = new LinkedHashMap<>();

        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            // Keep the first message per field; later ones are usually noise from the same
            // value failing several constraints.
            fieldErrors.putIfAbsent(
                    fieldError.getField(),
                    fieldError.getDefaultMessage()
            );
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("error", "VALIDATION_FAILED");
        response.put("message", "Request validation failed");
        response.put("fieldErrors", fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
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
