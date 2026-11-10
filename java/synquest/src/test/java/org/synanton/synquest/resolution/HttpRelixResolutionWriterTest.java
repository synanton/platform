package org.synanton.synquest.resolution;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.ServiceTokenMinter;
import org.synanton.common.resolution.ContractViolationException;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionOutcome;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.common.resolution.StaleResolutionException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpRelixResolutionWriterTest {

    private HttpServer server;
    private volatile int status = 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/graph/resolutions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void successIsAccepted() {
        assertThatCode(() -> writer().upsertResolution(decision())).doesNotThrowAnyException();
    }

    @Test
    void conflictMapsToStaleRejectedWork() {
        status = 409;
        responseBody = "stale fence";
        assertThatThrownBy(() -> writer().upsertResolution(decision()))
                .isInstanceOf(StaleResolutionException.class);
    }

    @Test
    void badRequestMapsToContractViolation() {
        status = 400;
        responseBody = "bad intent";
        assertThatThrownBy(() -> writer().upsertResolution(decision()))
                .isInstanceOf(ContractViolationException.class);
    }

    @Test
    void serverFailureMapsToRetryableInfrastructure() {
        status = 503;
        responseBody = "unavailable";
        assertThatThrownBy(() -> writer().upsertResolution(decision()))
                .isInstanceOf(RetryableProcessingException.class);
    }

    private HttpRelixResolutionWriter writer() {
        return new HttpRelixResolutionWriter("http://localhost:" + server.getAddress().getPort(),
                new ServiceTokenMinter("x".repeat(32)));
    }

    private static ResolutionDecision decision() {
        return new ResolutionDecision("tenant", "version", "mention", ResolutionOutcome.RESOLVED,
                "TEST", "entity", "label", 0.5, 0.1, "v1", "active", Map.of(), "RRF", 1);
    }
}
