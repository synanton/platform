package org.synanton.synflux.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.ServiceTokenMinter;
import org.synanton.common.resolution.RetryableProcessingException;

class HttpRelixResolutionLookupTest {

    private static final String SECRET = "relix-test-jwt-secret-at-least-32-bytes";
    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/resolutions/active", exchange -> respond(exchange,
                "{\"mentionIds\":[\"m1\"]}",
                "[{\"mentionId\":\"m1\",\"canonicalEntityId\":\"e1\",\"canonicalEntityType\":\"T\"}]"));
        server.createContext("/v1/extracted-relations/r1", exchange -> respond(exchange, "",
                "{\"relationId\":\"r1\",\"tenantId\":\"tenant-a\",\"sourceVersionId\":\"v1\","
                        + "\"subjectMentionId\":\"m1\",\"objectMentionId\":\"m2\","
                        + "\"predicate\":\"requires\",\"evidenceChunkId\":\"ref:0\"}"));
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsActiveResolutionsAndExtractedRelation() {
        var lookup = lookup();
        assertThat(lookup.getActiveResolutions("tenant-a", List.of("m1")))
                .singleElement().satisfies(resolution -> {
                    assertThat(resolution.canonicalEntityId()).isEqualTo("e1");
                    assertThat(resolution.canonicalEntityType()).isEqualTo("T");
                });
        assertThat(lookup.getExtractedRelation("tenant-a", "r1"))
                .get().satisfies(view -> {
                    assertThat(view.subjectMentionId()).isEqualTo("m1");
                    assertThat(view.predicate()).isEqualTo("requires");
                });
    }

    @Test
    void notFoundMeansMissingRelation() {
        assertThat(lookup().getExtractedRelation("tenant-a", "missing")).isEmpty();
    }

    @Test
    void serverFailureIsRetryable() {
        server.removeContext("/v1/resolutions/active");
        server.createContext("/v1/resolutions/active", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        assertThatThrownBy(() -> lookup().getActiveResolutions("tenant-a", List.of("m1")))
                .isInstanceOf(RetryableProcessingException.class);
    }

    private HttpRelixResolutionLookup lookup() {
        return new HttpRelixResolutionLookup(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                new ServiceTokenMinter(SECRET), new ObjectMapper());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String expectedBody,
                                String responseBody) throws java.io.IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        if (exchange.getRequestURI().getPath().startsWith("/v1/extracted-relations/")
                && exchange.getRequestURI().getPath().endsWith("missing")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
