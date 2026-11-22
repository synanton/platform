package org.synanton.relix.syntology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.ServiceTokenMinter;

class HttpSyntologyActiveVersionClientTest {

    private static final String SECRET = "relix-test-jwt-secret-at-least-32-bytes";
    private HttpServer server;
    private volatile int responseStatus;
    private volatile String responseBody;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void returnsActiveVersionAndImmutableReference() {
        respond(200, "{\"tenantId\":\"tenant-a\",\"activeVersion\":\"v1.2.0\","
                + "\"versionRef\":\"uuid:11111111-1111-1111-1111-111111111111\","
                + "\"activatedAt\":\"2026-11-11T00:00:00Z\"}");

        ActiveOntologyVersion version = client().getActiveVersion("tenant-a");

        assertThat(version.tenantId()).isEqualTo("tenant-a");
        assertThat(version.activeVersion()).isEqualTo("v1.2.0");
        assertThat(version.versionRef()).isEqualTo("uuid:11111111-1111-1111-1111-111111111111");
        assertThat(version.activatedAt()).isEqualTo(Instant.parse("2026-11-11T00:00:00Z"));
    }

    @Test
    void maps404ToUnavailable() {
        respond(404, "{\"error\":\"no_active_ontology\"}");
        assertThatThrownBy(() -> client().getActiveVersion("tenant-a"))
                .isInstanceOf(OntologyVersionUnavailableException.class);
    }

    @Test
    void maps401And403ToAuthFailure() {
        respond(401, "{}");
        assertThatThrownBy(() -> client().getActiveVersion("tenant-a"))
                .isInstanceOf(SyntologyAuthException.class);
        respond(403, "{}");
        assertThatThrownBy(() -> client().getActiveVersion("tenant-a"))
                .isInstanceOf(SyntologyAuthException.class);
    }

    @Test
    void maps503ToRetryableLookupFailure() {
        respond(503, "{}");
        assertThatThrownBy(() -> client().getActiveVersion("tenant-a"))
                .isInstanceOf(OntologyLookupFailedException.class);
    }

    @Test
    void mapsConnectionFailureToRetryableLookupFailure() {
        server.stop(0);
        assertThatThrownBy(() -> client().getActiveVersion("tenant-a"))
                .isInstanceOf(OntologyLookupFailedException.class);
    }

    private void respond(int status, String body) {
        responseStatus = status;
        responseBody = body;
    }

    private HttpSyntologyActiveVersionClient client() {
        return new HttpSyntologyActiveVersionClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                new ServiceTokenMinter(SECRET), new ObjectMapper());
    }
}
