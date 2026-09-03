package com.demo.payment_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** This service had no exception handler of its own until the billing-screen phase --
 * every route before this either always succeeded or was internal-only. Same
 * per-service-handler reasoning as every other service in this project: no shared
 * "commons" jar coupling independently deployable services back together. No Lombok
 * here, unlike auth-service/order-service -- this module has never depended on it. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvoiceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleInvoiceNotFound(InvoiceNotFoundException exception) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "INVOICE_NOT_FOUND");
        body.put("message", exception.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
}
