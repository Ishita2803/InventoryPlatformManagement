package com.demo.api_gateway_service.exception;

import com.demo.api_gateway_service.filter.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.net.ConnectException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns gateway-level failures into JSON.
 *
 * <p>The failure that matters here is a downstream service being unreachable. Left alone,
 * the caller gets Spring's HTML error page or a bare 500 — neither of which tells an API
 * client anything useful, and the HTML is actively wrong for a JSON API.
 *
 * <p>A dead downstream is **503, not 500**: the gateway is fine, the thing behind it is not,
 * and 503 is the status that tells a client the request is worth retrying. Returning 500
 * would suggest the request itself was broken.
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    /**
     * The downstream service could not be reached at all — not running, or no instance
     * registered in discovery.
     */
    @ExceptionHandler({ConnectException.class, IOException.class})
    public ResponseEntity<Map<String, Object>> handleDownstreamUnreachable(
            Exception exception, HttpServletRequest request) {

        log.error("Downstream unreachable for {} {}: {}",
                request.getMethod(), request.getRequestURI(), exception.toString());

        return body(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "The service handling this request is temporarily unavailable. Please retry.",
                request);
    }

    /**
     * No instance for the {@code lb://} service id. Distinct from the above, and worth its
     * own message: it usually means the target never registered with discovery rather than
     * that it is down.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleNoInstance(
            IllegalStateException exception, HttpServletRequest request) {

        log.error("No instance available for {} {}: {}",
                request.getMethod(), request.getRequestURI(), exception.toString());

        return body(HttpStatus.SERVICE_UNAVAILABLE, "NO_INSTANCE_AVAILABLE",
                "No instance of the target service is currently registered.",
                request);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code,
                                                     String message, HttpServletRequest request) {

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("error", code);
        response.put("message", message);
        response.put("path", request.getRequestURI());
        // Echoed so the caller can quote it when reporting the failure.
        response.put("correlationId", request.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER));

        return ResponseEntity.status(status).body(response);
    }
}
