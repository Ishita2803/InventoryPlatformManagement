package com.demo.order_service.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Honours the correlation id the gateway forwards on {@code POST /api/orders}, so this
 * service's own logs — and, via {@code OutboxWriter}, everything published from this
 * request — carry the same id the gateway logged. See the gateway's {@code CorrelationIdFilter}
 * for the full reasoning; duplicated here rather than shared for the same reason the Kafka
 * event classes are duplicated per service.
 *
 * <p>Only covers the synchronous HTTP entry point. Work that starts from a Kafka message
 * (the far more common case here) gets its correlation id from the message header instead —
 * see {@code InventoryResultListener}.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Threads are pooled and reused, so a stale id would leak into the next request.
            MDC.remove(MDC_KEY);
        }
    }
}
