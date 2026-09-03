package com.demo.api_gateway_service.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Verifies the JWT auth-service issues and forwards the decoded identity downstream as
 * headers -- the same "the gateway decides once, everyone downstream trusts the header"
 * shape as {@code CorrelationIdFilter}.
 *
 * <p><strong>Opt-in, not opt-out.</strong> Only paths explicitly listed in
 * {@link #ROUTE_ROLES} are gated. Every existing route from before Part D
 * (<code>/api/orders/**</code>, <code>/api/products/**</code>, <code>/demo.html</code>,
 * etc.) is untouched and stays exactly as open as it always was -- this project's Part A/B
 * demo does not suddenly require a login. New Part D routes opt into protection by being
 * added here as they're built, rather than everything being gated by default and needing
 * exceptions carved out.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * "METHOD PATTERN" -> roles allowed to call it. An empty set means "any authenticated
     * role." A pattern ending in {@code /**} is matched via {@link AntPathMatcher};
     * everything else is an exact match. Checked in map order, first match wins.
     * Method-specific entries let GET be open to more roles than a mutation on the same
     * path (browsing a vendor's products vs. editing them, say).
     */
    private static final Map<String, Set<String>> ROUTE_ROLES = new LinkedHashMap<>();

    static {
        ROUTE_ROLES.put("GET /auth/me", Set.of()); // any role, just needs to be a valid token

        // User-management phase: admin-only directory, edit, and direct password-set.
        // More specific than any pattern below, and there's no broader existing rule
        // for /auth/users/** to conflict with -- these are brand-new routes.
        ROUTE_ROLES.put("GET /auth/users", Set.of("ADMIN"));
        ROUTE_ROLES.put("PUT /auth/users/**", Set.of("ADMIN"));
        ROUTE_ROLES.put("POST /auth/users/**", Set.of("ADMIN"));
        ROUTE_ROLES.put("POST /api/vendor/onboard", Set.of("ADMIN"));
        ROUTE_ROLES.put("GET /api/vendor/vendors/**", Set.of("ADMIN"));
        // Admin can browse any vendor's catalog (needed to place a purchase order
        // against it, Phase D6) but never mutate one -- only the owning vendor can, and
        // vendor-service's own ownership check (ProductService.requireOwned) is the
        // second line of defence against a vendor mutating another vendor's product.
        ROUTE_ROLES.put("GET /api/vendor/products/**", Set.of("VENDOR", "ADMIN"));
        ROUTE_ROLES.put("POST /api/vendor/products/**", Set.of("VENDOR"));
        ROUTE_ROLES.put("PUT /api/vendor/products/**", Set.of("VENDOR"));
        ROUTE_ROLES.put("DELETE /api/vendor/products/**", Set.of("VENDOR"));

        ROUTE_ROLES.put("POST /api/customer/onboard", Set.of("ADMIN"));
        ROUTE_ROLES.put("GET /api/customer/customers/**", Set.of("ADMIN"));
        // Every mutation and listing here is the customer managing their own addresses
        // and end users -- no admin browsing need for these exists in the spec, unlike
        // vendor products (which admin needs to see to place a purchase order against).
        ROUTE_ROLES.put("POST /api/customer/addresses/**", Set.of("CUSTOMER"));
        ROUTE_ROLES.put("GET /api/customer/addresses/**", Set.of("CUSTOMER"));
        ROUTE_ROLES.put("DELETE /api/customer/addresses/**", Set.of("CUSTOMER"));
        ROUTE_ROLES.put("POST /api/customer/end-users/**", Set.of("CUSTOMER"));
        // More specific than the GET rule below, and declared first so it wins: this
        // lookup-by-id route has no ownership check of its own (it's meant for internal,
        // service-to-service calls that never go through the gateway at all, the same
        // as vendor-service's product-by-sku lookup) -- through the PUBLIC gateway,
        // restricting it to ADMIN closes what would otherwise be any authenticated
        // customer reading another customer's end-user data by guessing an id.
        ROUTE_ROLES.put("GET /api/customer/end-users/by-end-user-id/**", Set.of("ADMIN"));
        ROUTE_ROLES.put("GET /api/customer/end-users/**", Set.of("CUSTOMER"));
        ROUTE_ROLES.put("DELETE /api/customer/end-users/**", Set.of("CUSTOMER"));

        ROUTE_ROLES.put("POST /api/carrier/onboard", Set.of("ADMIN"));
        // Any authenticated role may browse the carrier directory and look up one
        // carrier's own details/tiers -- a customer needs both to pick a carrier and see
        // its weight tiers at checkout (the storefront's shipping-method selector).
        // Broadened from ADMIN-only once the storefront needed it; carrier name/code and
        // published tiers were never sensitive data the way a vendor's cost price is.
        ROUTE_ROLES.put("GET /api/carrier/carriers", Set.of());
        ROUTE_ROLES.put("GET /api/carrier/carriers/**", Set.of());
        ROUTE_ROLES.put("POST /api/carrier/weight-restrictions/**", Set.of("CARRIER"));
        ROUTE_ROLES.put("GET /api/carrier/weight-restrictions/**", Set.of("CARRIER"));
        ROUTE_ROLES.put("DELETE /api/carrier/weight-restrictions/**", Set.of("CARRIER"));

        // Phase D5: warehouses and catalog pricing, added to inventory-service. Existing
        // /api/inventory/** and /api/products/** routes are untouched and stay ungated --
        // only these two new admin mutations opt in.
        ROUTE_ROLES.put("POST /api/inventory/warehouses", Set.of("ADMIN"));
        ROUTE_ROLES.put("POST /api/inventory/catalog", Set.of("ADMIN"));

        // Phase D6: purchase orders, folded into order-service. Only admin places a
        // stocking order; controller-level logic scopes the GET to the caller's own
        // vendorId for VENDOR, all rows for ADMIN (business-id comes from the JWT claim
        // forwarded as X-User-Business-Id, never a client-supplied query param).
        ROUTE_ROLES.put("POST /api/purchase-orders", Set.of("ADMIN"));
        ROUTE_ROLES.put("GET /api/purchase-orders", Set.of("ADMIN", "VENDOR"));

        // Phase D10: a carrier's own assigned-orders view, for carrier.html. More specific
        // than -- and declared before -- the unlisted, still-ungated GET /api/orders/**
        // demo.html has always used unauthenticated; this new path is the only one gated.
        ROUTE_ROLES.put("GET /api/orders/assigned", Set.of("CARRIER"));

        // Phase D11: admin-only profit report, surfaced in admin.html.
        ROUTE_ROLES.put("GET /api/analytics/profit", Set.of("ADMIN"));

        // User-management/storefront phase: billing screen. ADMIN and CUSTOMER may read
        // any invoice by orderId -- ownership is NOT cross-checked against the caller's
        // JWT here, the same already-stated "client-supplied identifier" limitation
        // Phase D10 documents for customerId. Stated plainly, not hidden.
        ROUTE_ROLES.put("GET /api/payments/invoices/**", Set.of("ADMIN", "CUSTOMER"));

        // Customer's own order history -- a new, gated route (unlike GET /api/orders,
        // which stays ungated for demo.html), scoped server-side by X-User-Business-Id.
        ROUTE_ROLES.put("GET /api/orders/mine", Set.of("CUSTOMER"));
    }

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final SecretKey key;

    public JwtAuthFilter(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {

        Set<String> allowedRoles = matchRoute(request.getMethod(), request.getRequestURI());

        if (allowedRoles == null) {
            // Not a gated route -- pass through exactly as before Part D existed.
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            unauthorized(response, "Missing or malformed Authorization header");
            return;
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(header.substring(BEARER_PREFIX.length()))
                    .getPayload();

            String role = claims.get("role", String.class);

            if (!allowedRoles.isEmpty() && !allowedRoles.contains(role)) {
                forbidden(response, "Role " + role + " is not permitted on this route");
                return;
            }

            String businessId = claims.get("businessId", String.class);
            chain.doFilter(new UserContextRequest(request, claims.getSubject(), role, businessId), response);

        } catch (JwtException | IllegalArgumentException invalid) {
            unauthorized(response, "Invalid or expired token");
        }
    }

    /** Returns null for "not gated". */
    private Set<String> matchRoute(String method, String requestUri) {
        for (Map.Entry<String, Set<String>> entry : ROUTE_ROLES.entrySet()) {
            String[] methodAndPattern = entry.getKey().split(" ", 2);
            if (!methodAndPattern[0].equals(method)) {
                continue;
            }
            String pattern = methodAndPattern[1];
            boolean matches = pattern.endsWith("/**")
                    ? PATH_MATCHER.match(pattern, requestUri)
                    : pattern.equals(requestUri);
            if (matches) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    private void forbidden(HttpServletResponse response, String message) throws IOException {
        writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", message);
    }

    private void writeError(HttpServletResponse response, int status, String error, String message)
            throws IOException {

        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }

    /** Presents the decoded JWT claims as headers, the way CorrelationIdRequest does for one. */
    private static final class UserContextRequest extends HttpServletRequestWrapper {

        private final Map<String, String> overrides;

        private UserContextRequest(HttpServletRequest request, String username, String role, String businessId) {
            super(request);
            this.overrides = Map.of(
                    "X-User-Name", username == null ? "" : username,
                    "X-User-Role", role == null ? "" : role,
                    "X-User-Business-Id", businessId == null ? "" : businessId);
        }

        @Override
        public String getHeader(String name) {
            for (String overrideName : overrides.keySet()) {
                if (overrideName.equalsIgnoreCase(name)) {
                    return overrides.get(overrideName);
                }
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            for (String overrideName : overrides.keySet()) {
                if (overrideName.equalsIgnoreCase(name)) {
                    return Collections.enumeration(Set.of(overrides.get(overrideName)));
                }
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
            names.addAll(overrides.keySet());
            return Collections.enumeration(names);
        }
    }
}
