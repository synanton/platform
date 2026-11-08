package org.synanton.extraction.engine.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.extraction.engine.json.Json;

class LlmExtractionAdapterTest {

    private static final ExtractionContext CONTEXT = new ExtractionContext("tenant", "doc", Set.of());
    private static final String TEXT =
            "Project Halcyon-7 requires a helium leak check. The platform is offline.";
    private static final String MODEL_OUTPUT = """
            {"preEntities":[
              {"surfaceForm":"Halcyon-7","proposedType":"MaintenanceSystem","charStart":99,"charEnd":108},
              {"surfaceForm":"the platform","proposedType":"Concept","charStart":0,"charEnd":5},
              {"surfaceForm":"Halcyon-7","proposedType":"MaintenanceSystem","charStart":99,"charEnd":108}
            ],"relations":[
              {"subjectForm":"Halcyon-7","predicate":"requires","objectForm":"helium leak check",
               "evidenceQuote":"Halcyon-7 requires a helium leak check."}
            ]}
            """;

    private HttpServer server;
    private volatile String requestBody;
    private volatile int status = 200;
    private volatile String responseContent = MODEL_OUTPUT;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String body;
            if (status == 200) {
                body = "{\"choices\":[{\"message\":{\"content\":" + Json.quote(responseContent) + "}}]}";
            } else {
                body = "{\"error\":\"boom\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private LlmExtractionAdapter adapter() {
        return new LlmExtractionAdapter("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-model", Duration.ofSeconds(10));
    }

    @Test
    void recoversOffsetsDropsDuplicatesAndSynthesizesMissingEndpoints() {
        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        assertThat(result.preEntities()).hasSize(3);
        assertThat(result.preEntities()).allSatisfy(entity ->
                assertThat(TEXT.substring(entity.charStart(), entity.charEnd()))
                        .isEqualTo(entity.surfaceForm()));
        PreEntity halcyon = result.preEntities().stream()
                .filter(e -> e.surfaceForm().equals("Halcyon-7")).findFirst().orElseThrow();
        assertThat(halcyon.charStart()).isEqualTo(8);
        assertThat(halcyon.proposedType()).isEqualTo("MaintenanceSystem");

        PreEntity helium = result.preEntities().stream()
                .filter(e -> e.surfaceForm().equals("helium leak check")).findFirst().orElseThrow();
        assertThat(helium.proposedType()).isEqualTo("UNKNOWN");
        assertThat(helium.charStart()).isEqualTo(29);

        assertThat(result.relations()).hasSize(1);
        assertThat(result.relations().getFirst().predicate()).isEqualTo("requires");
        assertThat(result.relations().getFirst().evidenceCharStart()).isEqualTo(8);
    }

    @Test
    void keepsTheSegmentBaseOffset() {
        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 100), CONTEXT);
        PreEntity halcyon = result.preEntities().stream()
                .filter(e -> e.surfaceForm().equals("Halcyon-7")).findFirst().orElseThrow();
        assertThat(halcyon.charStart()).isEqualTo(108);
        assertThat(halcyon.charEnd()).isEqualTo(117);
    }

    @Test
    void sendsTheTunedPromptAndModel() {
        adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        Map<String, Object> request = Json.parseObject(requestBody);
        assertThat(request.get("model")).isEqualTo("test-model");
        List<Map<String, Object>> messages = Json.objectList(request, "messages");
        assertThat(messages).hasSize(2);
        assertThat(Json.string(messages.get(0), "content", ""))
                .contains("Allowed entity types")
                .contains("MaintenanceSystem")
                .contains("Allowed predicates");
        assertThat(Json.string(messages.get(1), "content", "")).contains(TEXT);
    }

    @Test
    void failsLoudlyOnHttpErrors() {
        status = 500;
        assertThatThrownBy(() -> adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void dropsUnanchorableCoreferences() {
        responseContent = "{\"preEntities\":[{\"surfaceForm\":\"they\",\"proposedType\":\"Person\"}],"
                + "\"relations\":[]}";
        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);
        assertThat(result.preEntities()).isEmpty();
    }
}
