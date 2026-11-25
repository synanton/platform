package org.synanton.extraction.compiler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyntologyReleaseClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resolvesActiveReleaseAndFetchesIrThroughProductionApi() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/tenants/demo/active-ontology-version", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                {"tenantId":"demo","ontologyId":"it-books","activeVersion":"it-books-v1",
                 "releaseId":"47fe38f8-9f72-481b-826a-6c5b12a6027a",
                 "releaseDigest":"3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c"}
                """);
        });
        server.createContext("/api/v1/admin/ontology/schemas/it-books-v1", exchange ->
            respond(exchange, 200, "{\"ontology\":{\"id\":\"it-books\"},\"classes\":[],\"relations\":[]}"));
        server.start();

        SyntologyReleaseClient client = new SyntologyReleaseClient(
            "http://localhost:" + server.getAddress().getPort(), "demo", "test-token");

        ReleaseIdentity active = client.resolveActive("it-books");

        assertThat(active.version()).isEqualTo("it-books-v1");
        assertThat(active.ontologyId()).isEqualTo("it-books");
        assertThat(client.fetchIr(active.version()).path("ontology").path("id").asText())
            .isEqualTo("it-books");
        assertThat(auth.get()).isEqualTo("Bearer test-token");
    }

    @Test
    void rejectsIdentityMismatchAndHttpFailures() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/tenants/demo/active-ontology-version", exchange ->
            respond(exchange, 200, """
                {"tenantId":"demo","ontologyId":"supply-chain","activeVersion":"1.0.0",
                 "releaseId":"id","releaseDigest":"3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c"}
                """));
        server.createContext("/api/v1/admin/ontology/schemas/missing", exchange ->
            respond(exchange, 404, "{\"error\":\"missing\"}"));
        server.start();

        SyntologyReleaseClient client = new SyntologyReleaseClient(
            "http://localhost:" + server.getAddress().getPort(), "demo", "test-token");

        assertThatThrownBy(() -> client.resolveActive("it-books"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("supply-chain");
        assertThatThrownBy(() -> client.fetchIr("missing"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("404");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
