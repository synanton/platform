package org.synanton.extraction.compiler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
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
    void resolvesReleaseByIdAndFetchesIrWithoutActiveLookup() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicInteger activeLookups = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/tenants/demo/ontology-releases/rel-1", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                {"tenantId":"demo","ontologyId":"it-books","version":"it-books-v1",
                 "releaseId":"47fe38f8-9f72-481b-826a-6c5b12a6027a",
                 "releaseDigest":"3a4a5edf876792ac92fa63494bd7181e4d8eb3826fb70e6fe4112d796be3831c",
                 "status":"ACTIVE","createdAt":"2026-11-25T09:08:54Z"}
                """);
        });
        server.createContext("/v1/tenants/demo/active-ontology-version", exchange -> {
            activeLookups.incrementAndGet();
            respond(exchange, 500, "{\"error\":\"active path must not be called\"}");
        });
        server.createContext("/api/v1/admin/ontology/schemas/it-books-v1", exchange ->
            respond(exchange, 200, "{\"ontology\":{\"id\":\"it-books\"},\"classes\":[],\"relations\":[]}"));
        server.start();

        SyntologyReleaseClient client = new SyntologyReleaseClient(
            "http://localhost:" + server.getAddress().getPort(), "demo", "test-token");

        ReleaseIdentity release = client.resolveRelease("rel-1");

        assertThat(release.version()).isEqualTo("it-books-v1");
        assertThat(release.ontologyId()).isEqualTo("it-books");
        assertThat(release.releaseId()).isEqualTo("47fe38f8-9f72-481b-826a-6c5b12a6027a");
        assertThat(client.fetchIr(release.version()).path("ontology").path("id").asText())
            .isEqualTo("it-books");
        assertThat(auth.get()).isEqualTo("Bearer test-token");
        assertThat(activeLookups).hasValue(0);
    }

    @Test
    void rejectsMissingReleaseAndHttpFailures() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/tenants/demo/ontology-releases/missing", exchange ->
            respond(exchange, 404, "{\"error\":\"no_release\"}"));
        server.createContext("/api/v1/admin/ontology/schemas/missing", exchange ->
            respond(exchange, 404, "{\"error\":\"missing\"}"));
        server.start();

        SyntologyReleaseClient client = new SyntologyReleaseClient(
            "http://localhost:" + server.getAddress().getPort(), "demo", "test-token");

        assertThatThrownBy(() -> client.resolveRelease("missing"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("404");
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
