package org.synanton.extraction.engine.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

    private static final String JSONL_OUTPUT = """
            {"type":"entity","surfaceForm":"Halcyon-7","proposedType":"MaintenanceSystem"}
            {"type":"entity","surfaceForm":"helium leak check","proposedType":"Concept"}
            {"type":"relation","subjectForm":"Halcyon-7","predicate":"requires","objectForm":"helium leak check","evidenceQuote":"Halcyon-7 requires a helium leak check."}
            """;

    private HttpServer server;
    private volatile String requestBody;
    private volatile int status = 200;
    private volatile String responseContent = MODEL_OUTPUT;
    private volatile String finishReason = "stop";
    private volatile boolean includeUsage = true;
    private volatile int promptTokens = 12;
    private volatile int completionTokens = 34;
    private volatile int totalTokens = 46;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String body;
            if (status == 200) {
                String choice = "{\"message\":{\"content\":" + Json.quote(responseContent)
                        + "},\"finish_reason\":" + Json.quote(finishReason) + "}";
                String usage = includeUsage
                        ? ",\"usage\":{\"prompt_tokens\":" + promptTokens
                        + ",\"completion_tokens\":" + completionTokens
                        + ",\"total_tokens\":" + totalTokens + "}"
                        : "";
                body = "{\"choices\":[" + choice + "]" + usage + "}";
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
    void promptUsesOnlyActiveOntologyVocabularyAndRelationContracts() {
        adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        Map<String, Object> request = Json.parseObject(requestBody);
        String prompt = Json.string(Json.objectList(request, "messages").getFirst(), "content", "");
        assertThat(prompt)
                .contains("Allowed entity types")
                .contains("MaintenanceSystem")
                .contains("Component")
                .contains("Concept")
                .contains("tested_every")
                .contains("replaced_every")
                .contains("records")
                .contains("requires")
                .contains("Component --tested_every--> Time")
                .contains("Concept --records--> Measurement")
                .doesNotContain("signs")
                .doesNotContain("Person");
    }

    @Test
    void capturesMissingColonAtReportedOffset(@TempDir Path captureDir) throws Exception {
        String malformed = missingColonAt436();
        responseContent = malformed;
        ExtractionContext context = new ExtractionContext(
                "tenant", "doc", Set.of(), "version-structural");
        promptTokens = 527;
        completionTokens = 9;
        totalTokens = 536;

        assertThatThrownBy(() -> new LlmExtractionAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-model", Duration.ofSeconds(10), 8192, captureDir)
                .extract(new TextSegment("c1", TEXT, 0), context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expected ':' at 436");

        Map<String, Object> event = onlyCapture(captureDir);
        assertThat(event.get("raw_response")).asString().contains("choices");
        assertThat(event.get("content")).isEqualTo(malformed);
        assertThat(event.get("parser_input")).isEqualTo(malformed);
        assertThat(event.get("parser_exception")).asString().contains("expected ':' at 436");
        assertThat(event.get("raw_response_sha256")).asString().hasSize(64);
        assertThat(event.get("content_sha256")).asString().hasSize(64);
        assertThat(event.get("parser_input_sha256")).asString().hasSize(64);
        assertThat(((Number) event.get("content_chars")).intValue()).isEqualTo(malformed.length());
        assertThat(event.get("finish_reason")).isEqualTo("stop");
        assertThat(((Number) event.get("prompt_tokens")).intValue()).isEqualTo(527);
        assertThat(event.get("source_version_id")).isEqualTo("version-structural");
        assertThat(event.get("chunk_id")).isEqualTo("c1");
    }

    @Test
    void unescapedQuoteInValueFailsBeforeCapture(@TempDir Path captureDir) {
        responseContent = "{\"preEntities\":[{\"surfaceForm\":\"Halcyon\"7\","
                + "\"proposedType\":\"Concept\"}],\"relations\":[]}";

        assertThatThrownBy(() -> new LlmExtractionAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-model", Duration.ofSeconds(10), 2048, captureDir)
                .extract(new TextSegment("c1", TEXT, 0), CONTEXT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rawNewlineInValueRemainsPermissive() {
        responseContent = "{\"preEntities\":[{\"surfaceForm\":\"line1\nline2\","
                + "\"proposedType\":\"Concept\"}],\"relations\":[]}";

        assertThatCode(() -> adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidUnicodeEscapeRemainsPermissive() {
        responseContent = "{\"preEntities\":[{\"surfaceForm\":\"A\\uZZZZ\","
                + "\"proposedType\":\"Concept\"}],\"relations\":[]}";

        assertThatCode(() -> adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT))
                .doesNotThrowAnyException();
    }

    @Test
    void truncatedBodyCapturesRawBodyWhenObjectExtractionFails(@TempDir Path captureDir) {
        responseContent = "{\"preEntities\":[{\"surfaceForm\":\"Halcyon-7\"";

        assertThatThrownBy(() -> new LlmExtractionAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-model", Duration.ofSeconds(10), 2048, captureDir)
                .extract(new TextSegment("c1", TEXT, 0), CONTEXT))
                .isInstanceOf(IllegalArgumentException.class);

        try {
            Map<String, Object> event = onlyCapture(captureDir);
            assertThat(event.get("raw_response")).asString().contains("choices");
            assertThat(event.get("content")).isEqualTo(responseContent);
            assertThat(event.get("parser_input")).asString().isEmpty();
            assertThat(event.get("parser_exception")).asString().contains("no valid JSONL records");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Map<String, Object> onlyCapture(Path captureDir) throws Exception {
        try (var files = Files.list(captureDir)) {
            Path captured = files.findFirst().orElseThrow();
            return Json.parseObject(Files.readString(captured));
        }
    }

    private static String missingColonAt436() {
        String head = "{\"pad\":\"";
        String tail = "\",\"preEntities\" ";
        int padding = 436 - head.length() - tail.length();
        String prefix = head + "x".repeat(padding) + tail;
        assert prefix.length() == 436;
        return prefix + "\"relations\":[]}";
    }

    @Test
    void capturesRawBodyAndParserInputOnParseFailure(@TempDir Path captureDir) throws Exception {
        ExtractionContext context = new ExtractionContext("tenant", "doc", Set.of(), "version-1");
        responseContent = "{\"preEntities\":[{\"surfaceForm\":\"Halcyon-7\"";
        finishReason = "stop";

        assertThatThrownBy(() -> new LlmExtractionAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-model", Duration.ofSeconds(10), 2048, captureDir)
                .extract(new TextSegment("c1", TEXT, 0), context))
                .isInstanceOf(IllegalArgumentException.class);

        try (var files = Files.list(captureDir)) {
            Path captured = files.findFirst().orElseThrow();
            Map<String, Object> event = Json.parseObject(Files.readString(captured));
            assertThat(event.get("raw_response")).asString().contains("preEntities");
            assertThat(event.get("parser_input")).asString().isEmpty();
            assertThat(event.get("parser_exception")).asString().contains("no valid JSONL records");
            assertThat(event.get("source_version_id")).isEqualTo("version-1");
            assertThat(event.get("chunk_id")).isEqualTo("c1");
            assertThat(event.get("raw_response_sha256")).asString().hasSize(64);
        }
    }

    @Test
    void usesConfiguredMaxTokensInTheRequest() {
        new LlmExtractionAdapter("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-model", Duration.ofSeconds(10), 4096)
                .extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        assertThat(Json.integer(Json.parseObject(requestBody), "max_tokens", -1)).isEqualTo(4096);
    }

    @Test
    void lengthResponseIsIncompleteBeforeJsonParsingAndPreservesUsage() {
        responseContent = "{\"preEntities\":[{\"surfaceForm\":\"Halcyon-7\"";
        finishReason = "length";
        promptTokens = 1328;
        completionTokens = 2048;
        totalTokens = 3376;

        assertThatThrownBy(() -> adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT))
                .isInstanceOf(LlmIncompleteOutputException.class)
                .satisfies(error -> {
                    LlmIncompleteOutputException incomplete = (LlmIncompleteOutputException) error;
                    assertThat(incomplete.classification()).isEqualTo("INCOMPLETE_LLM_OUTPUT");
                    assertThat(incomplete.usage().finishReason()).isEqualTo("length");
                    assertThat(incomplete.usage().promptTokens()).isEqualTo(1328);
                    assertThat(incomplete.usage().completionTokens()).isEqualTo(2048);
                    assertThat(incomplete.responseSha256()).hasSize(64);
                });
    }

    @Test
    void successfulResponsePreservesFinishReasonAndUsage() {
        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        assertThat(result.llmUsage().finishReason()).isEqualTo("stop");
        assertThat(result.llmUsage().promptTokens()).isEqualTo(12);
        assertThat(result.llmUsage().completionTokens()).isEqualTo(34);
        assertThat(result.llmUsage().totalTokens()).isEqualTo(46);
    }

    @Test
    void stopWithMalformedJsonRemainsAParsingFailure() {
        responseContent = "{\"preEntities\":[";
        finishReason = "stop";

        assertThatThrownBy(() -> adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(LlmIncompleteOutputException.class);
    }

    @Test
    void missingUsageIsDiagnosableWithoutFabricatingTokenCounts() {
        includeUsage = false;

        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        assertThat(result.llmUsage().finishReason()).isEqualTo("stop");
        assertThat(result.llmUsage().hasTokenCounts()).isFalse();
        assertThat(result.llmUsage().promptTokens()).isEqualTo(-1);
        assertThat(result.llmUsage().completionTokens()).isEqualTo(-1);
        assertThat(result.llmUsage().totalTokens()).isEqualTo(-1);
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

    @Test
    void parsesJsonlRecordsIndependently() {
        responseContent = JSONL_OUTPUT;

        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        assertThat(result.preEntities()).extracting(PreEntity::surfaceForm)
                .contains("Halcyon-7", "helium leak check");
        assertThat(result.relations()).hasSize(1);
        assertThat(result.quarantined()).isEmpty();
    }

    @Test
    void malformedJsonlMiddleLineDoesNotPoisonNeighbours() {
        String[] lines = JSONL_OUTPUT.split("\n");
        responseContent = lines[0] + "\n{broken}\n" + lines[2];

        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        assertThat(result.quarantined()).hasSize(1);
        assertThat(result.quarantined().getFirst().lineNumber()).isEqualTo(2);
        assertThat(result.quarantined().getFirst().reasonCode()).isEqualTo("JSON_PARSE");
        assertThat(result.preEntities()).extracting(PreEntity::surfaceForm).contains("Halcyon-7");
    }

    @Test
    void truncatedJsonlKeepsTheValidPrefixAndQuarantinesOnlyTheTail() {
        String[] lines = JSONL_OUTPUT.split("\n");
        responseContent = lines[0] + "\n" + lines[1] + "\n"
                + "{\"type\":\"relation\",\"subjectForm\":\"Halcyon-7\"";
        finishReason = "length";

        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        assertThat(result.preEntities()).hasSize(2);
        assertThat(result.quarantined()).hasSize(1);
        assertThat(result.quarantined().getFirst().lineNumber()).isEqualTo(3);
        assertThat(result.quarantined().getFirst().reasonCode()).isEqualTo("TRUNCATED");
        assertThat(result.llmUsage().finishReason()).isEqualTo("length");
    }

    @Test
    void concatenatedJsonlLineIsQuarantinedWithoutKillingTheNextLine() {
        String[] lines = JSONL_OUTPUT.split("\n");
        responseContent = lines[0] + lines[1] + "\n" + lines[2];

        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        assertThat(result.quarantined()).extracting(line -> line.reasonCode())
                .contains("CONCATENATED_RECORDS");
        assertThat(result.preEntities()).extracting(PreEntity::surfaceForm)
                .contains("helium leak check");
    }

    @Test
    void capturesQuarantineEvidenceAsJsonl(@TempDir Path captureDir) throws Exception {
        String[] lines = JSONL_OUTPUT.split("\n");
        responseContent = lines[0] + "\n{broken}\n" + lines[2];

        new LlmExtractionAdapter("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "test-model", Duration.ofSeconds(10), 2048, captureDir)
                .extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        Path quarantine;
        try (var files = Files.list(captureDir)) {
            quarantine = files.filter(path -> path.getFileName().toString().startsWith("quarantine-"))
                    .findFirst().orElseThrow();
        }
        List<String> rows = Files.readAllLines(quarantine);
        assertThat(rows).hasSize(1);
        Map<String, Object> event = Json.parseObject(rows.getFirst());
        assertThat(((Number) event.get("line")).intValue()).isEqualTo(2);
        assertThat(event.get("reason")).isEqualTo("JSON_PARSE");
        assertThat(event.get("raw")).isEqualTo("{broken}");
    }

    @Test
    void jsonlWithoutAnyValidLineStillThrowsIncompleteOnLength() {
        responseContent = "{\"type\":\"entity\",\"surfaceForm\":";
        finishReason = "length";

        assertThatThrownBy(() -> adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT))
                .isInstanceOf(LlmIncompleteOutputException.class);
    }

    @Test
    void promptAsksForJsonlRecords() {
        adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        Map<String, Object> request = Json.parseObject(requestBody);
        String prompt = Json.string(Json.objectList(request, "messages").getFirst(), "content", "");
        assertThat(prompt)
                .contains("Return JSONL")
                .contains("\"type\":\"entity\"")
                .contains("\"type\":\"relation\"")
                .doesNotContain("\"preEntities\"");
    }

    @Test
    void unknownEntityTypeIsQuarantined() {
        responseContent = "{\"type\":\"entity\",\"surfaceForm\":\"Halcyon-7\","
                + "\"proposedType\":\"Alien\"}";
        ExtractionContext restricted = new ExtractionContext("tenant", "doc", Set.of("Organization"));

        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 0), restricted);

        assertThat(result.preEntities()).isEmpty();
        assertThat(result.quarantined()).extracting(line -> line.reasonCode())
                .containsExactly("UNKNOWN_ENTITY_TYPE");
    }

    @Test
    void unknownPredicateIsQuarantined() {
        responseContent = "{\"type\":\"relation\",\"subjectForm\":\"Halcyon-7\","
                + "\"predicate\":\"invented\",\"objectForm\":\"helium leak check\","
                + "\"evidenceQuote\":\"Halcyon-7 requires a helium leak check.\"}";

        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        assertThat(result.relations()).isEmpty();
        assertThat(result.quarantined()).extracting(line -> line.reasonCode())
                .containsExactly("UNKNOWN_PREDICATE");
    }

    @Test
    void unanchorableSurfaceIsQuarantinedWithoutLosingValidNeighbours() {
        responseContent = "{\"type\":\"entity\",\"surfaceForm\":\"Halcyon-7\","
                + "\"proposedType\":\"MaintenanceSystem\"}\n"
                + "{\"type\":\"entity\",\"surfaceForm\":\"not in the text\","
                + "\"proposedType\":\"Concept\"}";

        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        assertThat(result.preEntities()).extracting(PreEntity::surfaceForm).containsExactly("Halcyon-7");
        assertThat(result.quarantined()).extracting(line -> line.reasonCode())
                .containsExactly("UNANCHORED");
    }

    @Test
    void duplicateEntityIsQuarantined() {
        String entity = "{\"type\":\"entity\",\"surfaceForm\":\"Halcyon-7\","
                + "\"proposedType\":\"MaintenanceSystem\"}";
        responseContent = entity + "\n" + entity;

        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        assertThat(result.preEntities()).hasSize(1);
        assertThat(result.quarantined()).extracting(line -> line.reasonCode())
                .containsExactly("DUPLICATE");
    }

    @Test
    void selfRelationIsQuarantined() {
        responseContent = "{\"type\":\"relation\",\"subjectForm\":\"Halcyon-7\","
                + "\"predicate\":\"requires\",\"objectForm\":\"Halcyon-7\","
                + "\"evidenceQuote\":\"Halcyon-7 requires a helium leak check.\"}";

        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        assertThat(result.relations()).isEmpty();
        assertThat(result.quarantined()).extracting(line -> line.reasonCode())
                .containsExactly("SELF_RELATION");
    }

    @Test
    void emptyResponseForNoFactsIsAValidEmptyResult() {
        responseContent = "";

        ExtractionResult result = adapter().extract(new TextSegment("c1", TEXT, 0), CONTEXT);

        assertThat(result.preEntities()).isEmpty();
        assertThat(result.relations()).isEmpty();
        assertThat(result.quarantined()).isEmpty();
    }
}
