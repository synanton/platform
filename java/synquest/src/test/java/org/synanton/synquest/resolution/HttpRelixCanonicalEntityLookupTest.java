package org.synanton.synquest.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.ServiceTokenMinter;
import org.synanton.common.resolution.RetryableProcessingException;

class HttpRelixCanonicalEntityLookupTest {

    private static final String SECRET = "relix-test-jwt-secret-at-least-32-bytes";
    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "{\"entity_id\":\"e1\",\"type\":\"MaintenanceSystem\"}";
    private volatile String authorization;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/entities/e1/type", exchange -> {
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void returnsTheCurrentTypeAndSendsATenantToken() {
        var lookup = lookup();
        assertThat(lookup.getActiveEntityType("tenant-a", "e1")).contains("MaintenanceSystem");
        assertThat(authorization).startsWith("Bearer ");
    }

    @Test
    void notFoundMeansTheProjectionIsStale() {
        status = 404;
        assertThat(lookup().getActiveEntityType("tenant-a", "e1")).isEmpty();
    }

    @Test
    void serverFailureIsRetryableNotNoMatch() {
        status = 500;
        assertThatThrownBy(() -> lookup().getActiveEntityType("tenant-a", "e1"))
                .isInstanceOf(RetryableProcessingException.class);
    }

    private HttpRelixCanonicalEntityLookup lookup() {
        return new HttpRelixCanonicalEntityLookup(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                new ServiceTokenMinter(SECRET), new ObjectMapper());
    }
}
