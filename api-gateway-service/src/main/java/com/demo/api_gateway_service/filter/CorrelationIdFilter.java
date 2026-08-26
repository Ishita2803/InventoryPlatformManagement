package com.demo.api_gateway_service.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Gives every request an id that follows it downstream, and logs its outcome.
 *
 * <p>Without this, a failure is three unrelated log lines in three services with no way to
 * tell which belong to the same request. With it, one id appears in the gateway's log, in
 * the header the downstream service receives, and in the response the caller holds — so a
 * customer quoting an id is enough to find the whole trail.
 *
 * <p>An existing {@code X-Correlation-Id} is honoured rather than replaced, so an id
 * assigned further upstream (a load balancer, a mobile client) survives.
 *
 * <p>This is deliberately not full distributed tracing. Micrometer Tracing with a real
 * backend is Phase 9; this is the cheap 90% that costs one filter and no infrastructure.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        // On the response before the chain runs: once the proxied response has been
        // committed the headers can no longer be changed.
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        long startedAt = System.nanoTime();

        try {
            // Wrapped so the gateway forwards the header to the downstream service. Setting
            // it only on the response would identify the request to the caller and to
            // nobody else.
            chain.doFilter(new CorrelationIdRequest(request, correlationId), response);

        } finally {
            long millis = (System.nanoTime() - startedAt) / 1_000_000;

            log.info("{} {} -> {} ({} ms)",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), millis);

            // Threads are pooled and reused, so a stale id would leak into the next
            // request handled by this thread.
            MDC.remove(MDC_KEY);
        }
    }

    /** Presents the correlation id as though the caller had sent it. */
    private static final class CorrelationIdRequest extends HttpServletRequestWrapper {

        private final String correlationId;

        private CorrelationIdRequest(HttpServletRequest request, String correlationId) {
            super(request);
            this.correlationId = correlationId;
        }

        @Override
        public String getHeader(String name) {
            return CORRELATION_ID_HEADER.equalsIgnoreCase(name)
                    ? correlationId
                    : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return CORRELATION_ID_HEADER.equalsIgnoreCase(name)
                    ? Collections.enumeration(Set.of(correlationId))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
            names.add(CORRELATION_ID_HEADER);
            return Collections.enumeration(names);
        }
    }
}
