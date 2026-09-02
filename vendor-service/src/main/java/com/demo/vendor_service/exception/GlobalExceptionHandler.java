package com.demo.vendor_service.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(VendorNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleVendorNotFound(VendorNotFoundException exception) {
        return body(HttpStatus.NOT_FOUND, "VENDOR_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleProductNotFound(ProductNotFoundException exception) {
        return body(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(DuplicateSkuException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateSku(DuplicateSkuException exception) {
        return body(HttpStatus.CONFLICT, "DUPLICATE_SKU", exception.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ForbiddenException exception) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {

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

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("error", code);
        response.put("message", message);
        return ResponseEntity.status(status).body(response);
    }
}
