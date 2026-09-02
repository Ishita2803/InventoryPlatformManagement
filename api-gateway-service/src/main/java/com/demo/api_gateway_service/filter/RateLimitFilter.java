package com.demo.api_gateway_service.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fixed-window, per-client-IP rate limit — deliberately hand-rolled rather than reached for
 * a library.
 *
 * <p>Spring Cloud Gateway's built-in {@code RequestRateLimiter} needs Redis to hold counters,
 * which would mean standing up a whole stateful dependency solely to rate-limit one gateway
 * that Phase 16 exposes for the first time. A single-instance, in-memory counter is honest
 * about what it is: it resets if the pod restarts, and it does not coordinate across replicas
 * (there is currently only one). Both are acceptable for what this protects against here —
 * a script hammering the one public endpoint — and both would need re-solving the moment a
 * second gateway replica exists.
 *
 * <p>Ordered directly after {@link CorrelationIdFilter} so a rejected request still carries a
 * correlation id in its log line and its response body.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final int limitPerWindow;
    private final long windowMillis;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${gateway.rate-limit.requests-per-window:20}") int limitPerWindow,
            @Value("${gateway.rate-limit.window-seconds:1}") long windowSeconds) {
        this.limitPerWindow = limitPerWindow;
        this.windowMillis = windowSeconds * 1000;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {

        String clientKey = request.getRemoteAddr();
        long now = System.currentTimeMillis();

        Window window = windows.computeIfAbsent(clientKey, key -> new Window(now));

        // A window is reused within its lifetime and replaced, not reset in place, once it
        // has expired -- replacing avoids a racing thread seeing a half-reset counter.
        if (now - window.startedAt >= windowMillis) {
            window = windows.compute(clientKey, (key, existing) ->
                    (existing == null || now - existing.startedAt >= windowMillis)
                            ? new Window(now)
                            : existing);
        }

        int countSoFar = window.count.incrementAndGet();

        if (countSoFar > limitPerWindow) {
            long retryAfterSeconds = Math.max(1,
                    (windowMillis - (now - window.startedAt) + 999) / 1000);

            log.warn("Rate limit exceeded for {}: {} {} ({} requests in the current window)",
                    clientKey, request.getMethod(), request.getRequestURI(), countSoFar);

            respondTooManyRequests(response, request, retryAfterSeconds);
            return;
        }

        chain.doFilter(request, response);
    }

    private void respondTooManyRequests(HttpServletResponse response, HttpServletRequest request,
                                         long retryAfterSeconds) throws IOException {

        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        String correlationId = response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        Map<String, Object> body = Map.of(
                "error", "TOO_MANY_REQUESTS",
                "message", "Rate limit exceeded. Please slow down and retry after "
                        + retryAfterSeconds + "s.",
                "path", request.getRequestURI(),
                "correlationId", correlationId == null ? "" : correlationId);

        response.getWriter().write(toJson(body));
    }

    /** No Jackson dependency needed for four fixed, already-safe keys. */
    private String toJson(Map<String, Object> body) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey()).append("\":\"")
                    .append(entry.getValue()).append('"');
        }
        return json.append('}').toString();
    }

    /**
     * Evicts windows untouched for well past their own lifetime, so a public endpoint being
     * hit by many distinct client IPs does not grow this map without bound.
     */
    @Scheduled(fixedDelay = 60_000)
    void evictStaleWindows() {
        long cutoff = System.currentTimeMillis() - (windowMillis * 10);
        windows.entrySet().removeIf(entry -> entry.getValue().startedAt < cutoff);
    }

    private static final class Window {
        private final long startedAt;
        private final AtomicInteger count = new AtomicInteger(0);

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
