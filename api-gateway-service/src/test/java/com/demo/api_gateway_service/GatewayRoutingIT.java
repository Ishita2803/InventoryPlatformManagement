package com.demo.api_gateway_service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway actually proxying, against a real HTTP server.
 *
 * <p>The downstream is a bare {@code com.sun.net.httpserver.HttpServer} rather than a mock —
 * it costs no dependency and, unlike a mock, it can be asked what headers it genuinely
 * received over the wire. That is the only way to prove the correlation id is *forwarded*
 * rather than merely echoed back to the caller.
 *
 * <p>The client is the JDK's {@link HttpClient} for the same reason: real HTTP, and no
 * argument with where Boot 4 moved {@code TestRestTemplate} to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingIT {

    private static HttpServer stub;

    /** What the downstream saw, so the test can assert on the proxied request. */
    private static final AtomicReference<String> receivedCorrelationId = new AtomicReference<>();

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void gatewayRoutes(DynamicPropertyRegistry registry) throws IOException {

        stub = HttpServer.create(new InetSocketAddress(0), 0);

        stub.createContext("/api/stub", exchange -> {
            receivedCorrelationId.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            byte[] payload = "{\"from\":\"downstream\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });

        stub.start();
        int stubPort = stub.getAddress().getPort();

        registry.add("spring.cloud.gateway.server.webmvc.routes[0].id", () -> "stub");
        registry.add("spring.cloud.gateway.server.webmvc.routes[0].uri",
                () -> "http://localhost:" + stubPort);
        registry.add("spring.cloud.gateway.server.webmvc.routes[0].predicates[0]",
                () -> "Path=/api/stub/**");

        // Port 1 has nothing behind it: the "downstream is down" case.
        registry.add("spring.cloud.gateway.server.webmvc.routes[1].id", () -> "dead");
        registry.add("spring.cloud.gateway.server.webmvc.routes[1].uri", () -> "http://localhost:1");
        registry.add("spring.cloud.gateway.server.webmvc.routes[1].predicates[0]",
                () -> "Path=/api/dead/**");
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Test
    @DisplayName("a matching path is proxied to the downstream service")
    void proxiesMatchingPath() {

        HttpResponse<String> response = get("/api/stub/thing", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("downstream");
    }

    @Test
    @DisplayName("a correlation id is generated, returned to the caller AND sent downstream")
    void generatesAndForwardsCorrelationId() {

        receivedCorrelationId.set(null);

        HttpResponse<String> response = get("/api/stub/thing", null);

        String returned = response.headers().firstValue("X-Correlation-Id").orElse(null);
        assertThat(returned).isNotBlank();

        // The part that matters: the downstream saw the same id. Echoing it only to the
        // caller would identify the request to precisely one participant.
        assertThat(receivedCorrelationId.get()).isEqualTo(returned);
    }

    @Test
    @DisplayName("an incoming correlation id is preserved, not replaced")
    void preservesIncomingCorrelationId() {

        receivedCorrelationId.set(null);

        HttpResponse<String> response = get("/api/stub/thing", "caller-supplied-id");

        // An id assigned further upstream must survive, or the trail breaks at our edge.
        assertThat(response.headers().firstValue("X-Correlation-Id"))
                .contains("caller-supplied-id");
        assertThat(receivedCorrelationId.get()).isEqualTo("caller-supplied-id");
    }

    @Test
    @DisplayName("an unreachable downstream is 503 with JSON, not 500 and not an HTML page")
    void unreachableDownstreamReturns503Json() {

        HttpResponse<String> response = get("/api/dead/thing", null);

        // 503, because the gateway is fine and the thing behind it is not — which also tells
        // the client the request is worth retrying.
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("SERVICE_UNAVAILABLE");
        assertThat(response.body()).contains("correlationId");
        assertThat(response.body()).doesNotContain("<html");
    }

    @Test
    @DisplayName("an unrouted path is 404 and never reaches a downstream service")
    void unroutedPathIsNotFound() {

        HttpResponse<String> response = get("/nope", null);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path, String correlationId) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + path))
                    .GET();

            if (correlationId != null) {
                request.header("X-Correlation-Id", correlationId);
            }

            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());

        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError("Request to " + path + " failed", failure);
        }
    }
}
