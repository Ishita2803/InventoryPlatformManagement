package com.demo.api_gateway_service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate limiter against a real HTTP round trip, with the window shrunk to something a test
 * can exhaust and wait out in well under a second.
 *
 * <p>The limit is set low enough (3 requests / 200ms) that the test does not depend on how
 * fast the test JVM happens to run, only on making more than 3 requests before 200ms elapses
 * -- trivial for a tight loop against localhost.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.rate-limit.requests-per-window=3",
                "gateway.rate-limit.window-seconds=1"
        })
// Each test method must start with a clean rate-limit counter, and the filter's window map
// is per-context singleton state -- without this, tests would see each other's request
// counts depending on run order.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RateLimitFilterIT {

    private static HttpServer stub;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void gatewayRoutes(DynamicPropertyRegistry registry) throws IOException {

        stub = HttpServer.create(new InetSocketAddress(0), 0);

        stub.createContext("/api/stub", exchange -> {
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
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Test
    @DisplayName("requests within the window limit are proxied normally")
    void requestsWithinLimitSucceed() {

        for (int i = 0; i < 3; i++) {
            assertThat(get().statusCode()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("the request that exceeds the window limit gets 429 with JSON, a Retry-After, and never reaches the downstream")
    void exceedingLimitReturns429() {

        for (int i = 0; i < 3; i++) {
            get();
        }

        HttpResponse<String> rejected = get();

        assertThat(rejected.statusCode()).isEqualTo(429);
        assertThat(rejected.body()).contains("TOO_MANY_REQUESTS");
        assertThat(rejected.body()).contains("correlationId");
        assertThat(rejected.headers().firstValue("Retry-After")).isPresent();
        assertThat(rejected.body()).doesNotContain("downstream");
    }

    @Test
    @DisplayName("the limit resets once the window elapses")
    void limitResetsAfterWindow() throws InterruptedException {

        for (int i = 0; i < 3; i++) {
            get();
        }
        assertThat(get().statusCode()).isEqualTo(429);

        // The configured window is 1s; wait past it, then confirm traffic flows again.
        Thread.sleep(1100);

        assertThat(get().statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/stub/thing"))
                    .GET()
                    .build();

            return http.send(request, HttpResponse.BodyHandlers.ofString());

        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError("Request failed", failure);
        }
    }
}
