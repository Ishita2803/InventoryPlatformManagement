package com.demo.inventory_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InventoryNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleInventoryNotFound(
            InventoryNotFoundException exception
    ) {

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "error", "INVENTORY_NOT_FOUND",
                        "message", exception.getMessage()
                ));
    }

    @ExceptionHandler(InsufficientInventoryException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientInventory(
            InsufficientInventoryException exception
    ) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "error", "INSUFFICIENT_INVENTORY",
                        "message", exception.getMessage()
                ));
    }
}