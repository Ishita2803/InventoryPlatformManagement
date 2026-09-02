package com.demo.carrier_service.exception;

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

    @ExceptionHandler(CarrierNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleCarrierNotFound(CarrierNotFoundException exception) {
        return body(HttpStatus.NOT_FOUND, "CARRIER_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(DuplicateCarrierCodeException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateCarrierCode(DuplicateCarrierCodeException exception) {
        return body(HttpStatus.CONFLICT, "DUPLICATE_CARRIER_CODE", exception.getMessage());
    }

    @ExceptionHandler(WeightTierNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleWeightTierNotFound(WeightTierNotFoundException exception) {
        return body(HttpStatus.NOT_FOUND, "WEIGHT_TIER_NOT_FOUND", exception.getMessage());
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
