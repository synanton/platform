package org.synanton.extraction.engine.llm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionEngineAdapter;
import org.synanton.extraction.engine.ExtractionIntegrity;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.LlmProvenance;
import org.synanton.extraction.engine.LlmUsage;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.QuarantinedLine;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.extraction.engine.json.Json;
import org.synanton.extraction.engine.jsonl.JsonlFailureReason;
import org.synanton.extraction.engine.jsonl.JsonlLine;
import org.synanton.extraction.engine.jsonl.JsonlLineParser;
import org.synanton.extraction.engine.jsonl.JsonlParseResult;
import org.synanton.extraction.engine.jsonl.JsonlRejectedLine;
import org.synanton.extraction.engine.jsonl.JsonlWriter;
import org.synanton.extraction.engine.provenance.PromptDigest;

/**
 * RAKG Phase B adapter: calls an OpenAI-compatible vLLM endpoint and maps JSONL
 * records to the engine-neutral extraction contract.
 *
 * <p>Each line is parsed independently. A malformed line is quarantined with a
 * line number and reason code; valid neighbours still produce candidates. A
 * truncated final line is recoverable when earlier lines are valid. The legacy
 * monolithic object is accepted only as a compatibility fallback when no JSONL
 * line is accepted.
 *
 * <p>The model is not asked to be authoritative about character offsets. Each
 * returned surface form is anchored in the segment (the requested offset is only
 * a hint), so invalid counts, repeated mentions, and missing endpoints are
 * handled deterministically. Unanchorable forms (typically pronouns) are
 * quarantined; resolution remains downstream.
 *
 * <p>Prompt version: {@code prompts/extraction-v2.txt}. The module stays
 * JDK-only so the evaluator CLI can run with the main class output.
 */
public final class LlmExtractionAdapter implements ExtractionEngineAdapter {

    public static final String PROMPT_VERSION = "extraction-v2";
    private static final int DEFAULT_MAX_TOKENS = 2048;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final JsonlLineParser JSONL_PARSER = new JsonlLineParser();

    static final String DEFAULT_TYPES = String.join(", ",
            "Organization", "Supplier", "Customer", "Product", "Warehouse", "Order", "OrderLine",
            "Shipment", "InventoryItem", "Location", "Address", "Country", "City", "PurchaseOrder",
            "SalesOrder", "Carrier", "Contract", "Invoice", "Payment", "MaintenanceSystem",
            "Component", "Procedure", "Document", "Measurement", "Time", "Concept");

    static final String DEFAULT_PREDICATES = String.join(", ",
            "hasSupplier", "hasCustomer", "locatedIn", "containsProduct", "partOfOrder",
            "shipsVia", "fulfillsOrder", "tested_every", "replaced_every", "records", "requires");

    private static final Set<String> ALLOWED_PREDICATES = Set.of(DEFAULT_PREDICATES.split(",\\s*"));

    static final String RELATION_CONTRACT = """
            Product --hasSupplier--> Supplier
            Order --hasCustomer--> Customer
            Warehouse --locatedIn--> Location
            OrderLine --containsProduct--> Product
            OrderLine --partOfOrder--> Order
            Shipment --shipsVia--> Carrier
            Shipment --fulfillsOrder--> Order
            Component --tested_every--> Time
            Component --replaced_every--> Time
            Concept --records--> Measurement
            MaintenanceSystem --requires--> Component
            """.strip();

    private final HttpClient client;
    private final String endpoint;
    private final String model;
    private final Duration timeout;
    private final int maxTokens;
    /**
     * Shared diagnostic capture directory. This is process-wide, not
     * tenant-scoped; it is disabled by default and must not be enabled in
     * production without extending the D5 tenant-isolation/retention controls.
     */
    private final Path captureDir;
    private final String promptVersion;
    private final String promptTemplate;

    public LlmExtractionAdapter(String endpoint, String model) {
        this(endpoint, model, Duration.ofSeconds(120), DEFAULT_MAX_TOKENS, null);
    }

    public LlmExtractionAdapter(String endpoint, String model, Duration timeout) {
        this(endpoint, model, timeout, DEFAULT_MAX_TOKENS, null);
    }

    public LlmExtractionAdapter(String endpoint, String model, Duration timeout, int maxTokens) {
        this(endpoint, model, timeout, maxTokens, null);
    }

    public LlmExtractionAdapter(String endpoint, String model, Duration timeout, int maxTokens,
                                Path captureDir) {
        this(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10)).build(),
                endpoint, model, timeout, maxTokens, captureDir);
    }

    public LlmExtractionAdapter(String endpoint, String model, Duration timeout, int maxTokens,
                                Path captureDir, String promptVersion) {
        this(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10)).build(),
                endpoint, model, timeout, maxTokens, captureDir, promptVersion);
    }

    public LlmExtractionAdapter(HttpClient client, String endpoint, String model, Duration timeout) {
        this(client, endpoint, model, timeout, DEFAULT_MAX_TOKENS, null);
    }

    public LlmExtractionAdapter(HttpClient client, String endpoint, String model,
                                Duration timeout, int maxTokens) {
        this(client, endpoint, model, timeout, maxTokens, null);
    }

    public LlmExtractionAdapter(HttpClient client, String endpoint, String model,
                                Duration timeout, int maxTokens, Path captureDir) {
        this(client, endpoint, model, timeout, maxTokens, captureDir, PROMPT_VERSION);
    }

    public LlmExtractionAdapter(HttpClient client, String endpoint, String model,
                                Duration timeout, int maxTokens, Path captureDir,
                                String promptVersion) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("LLM extraction endpoint is required");
        }
        this.client = client;
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.model = model == null || model.isBlank() ? "qwen3-extraction" : model;
        this.timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        this.maxTokens = Math.max(1, maxTokens);
        this.captureDir = captureDir;
        this.promptVersion = normalizePromptVersion(promptVersion);
        this.promptTemplate = loadPrompt(this.promptVersion);
    }

    public String promptVersion() {
        return promptVersion;
    }

    /** The diagnostic capture directory, or empty when capture is disabled. */
    public Optional<Path> captureDirectory() {
        return Optional.ofNullable(captureDir);
    }

    @Override
    public ExtractionResult extract(TextSegment segment, ExtractionContext context) {
        String text = segment.text() == null ? "" : segment.text();
        if (text.isBlank()) {
            return ExtractionResult.empty();
        }
        int base = segment.charOffsetStart();
        String compiledPrompt = buildPrompt(context);
        String compiledDigest = PromptDigest.digest(compiledPrompt);
        String renderedDigest = PromptDigest.verify(compiledPrompt, compiledPrompt);
        LlmProvenance provenance = new LlmProvenance(
                PROMPT_VERSION, compiledDigest, renderedDigest, null, null);
        LlmCallResult call = call(compiledPrompt, text);
        String content = call.content();
        JsonlParseResult parsed = JSONL_PARSER.parse(content, call.usage().finishReason());

        if (!parsed.hasAccepted() && parsed.rejected().isEmpty()) {
            if (call.usage().isIncomplete()) {
                throwIncompleteOrParseFailure(call, new IllegalArgumentException("empty JSONL response"));
            }
            // The prompt allows an empty response when there are no facts.
            return new ExtractionResult(List.of(), List.of(), call.usage(), List.of(),
                    ExtractionIntegrity.CLEAN, provenance);
        }
        if (!parsed.hasAccepted() && looksLikeLegacyObject(content)) {
            return parseLegacyObject(segment, context, call, content, provenance);
        }
        if (!parsed.hasAccepted()) {
            RuntimeException failure = new IllegalArgumentException(
                    "no valid JSONL records: " + summarizeRejections(parsed.rejected()));
            captureParseFailure(segment, context, call, content, null, failure);
            throwIncompleteOrParseFailure(call, failure);
        }

        List<PreEntity> entities = new ArrayList<>();
        Map<String, PreEntity> bySurface = new LinkedHashMap<>();
        Set<String> spans = new LinkedHashSet<>();
        List<RelationCandidate> relations = new ArrayList<>();
        Set<String> relationKeys = new LinkedHashSet<>();
        List<QuarantinedLine> quarantined = new ArrayList<>();
        for (JsonlRejectedLine rejected : parsed.rejected()) {
            quarantined.add(new QuarantinedLine(rejected.lineNumber(), rejected.raw(),
                    rejected.reason().name(), rejected.detail(),
                    rejected.reason().category().name()));
        }
        for (JsonlLine line : parsed.accepted()) {
            Map<String, Object> item = line.value();
            String kind = Json.string(item, "type", "");
            if ("entity".equals(kind)) {
                mapEntity(item, line.lineNumber(), line.raw(), segment, context, text, base,
                        entities, bySurface, spans, quarantined);
            } else if ("relation".equals(kind)) {
                mapRelation(item, line.lineNumber(), line.raw(), segment, text, base,
                        entities, bySurface, spans, relations, relationKeys, quarantined);
            }
        }
        if (!quarantined.isEmpty()) {
            captureQuarantine(segment, context, call, quarantined);
        }
        ExtractionIntegrity integrity = integrity(quarantined);
        if (call.usage().isIncomplete() && integrity == ExtractionIntegrity.CLEAN) {
            integrity = ExtractionIntegrity.TRUNCATED;
        }
        return new ExtractionResult(List.copyOf(entities), List.copyOf(relations), call.usage(),
                List.copyOf(quarantined), integrity, provenance);
    }

    private static ExtractionIntegrity integrity(List<QuarantinedLine> quarantined) {
        boolean truncated = false;
        boolean corrupted = false;
        for (QuarantinedLine line : quarantined) {
            if (!"STRUCTURAL".equals(line.category())) {
                continue;
            }
            if ("TRUNCATED".equals(line.reasonCode())) {
                truncated = true;
            } else {
                corrupted = true;
            }
        }
        if (corrupted) {
            return ExtractionIntegrity.CORRUPTED;
        }
        return truncated ? ExtractionIntegrity.TRUNCATED : ExtractionIntegrity.CLEAN;
    }

    private void mapEntity(Map<String, Object> item, int lineNumber, String raw,
                           TextSegment segment, ExtractionContext context, String text, int base,
                           List<PreEntity> entities, Map<String, PreEntity> bySurface,
                           Set<String> spans, List<QuarantinedLine> quarantined) {
        String surface = Json.string(item, "surfaceForm", "").strip();
        if (surface.isEmpty()) {
            quarantine(quarantined, lineNumber, raw, JsonlFailureReason.EMPTY_FIELD, "surfaceForm is blank");
            return;
        }
        int hint = Json.integer(item, "charStart", -1);
        int local = locate(text, surface, hint);
        if (local < 0) {
            quarantine(quarantined, lineNumber, raw, JsonlFailureReason.UNANCHORED, surface);
            return;
        }
        surface = text.substring(local, local + surface.length());
        int start = base + local;
        int end = start + surface.length();
        if (!spans.add(start + ":" + end)) {
            quarantine(quarantined, lineNumber, raw, JsonlFailureReason.DUPLICATE, surface);
            return;
        }
        String type = Json.string(item, "proposedType", "Concept").strip();
        if (type.isEmpty()) {
            type = "Concept";
        }
        if (!context.allowedEntityTypes().isEmpty()
                && !context.allowedEntityTypes().contains(type)) {
            quarantine(quarantined, lineNumber, raw, JsonlFailureReason.UNKNOWN_ENTITY_TYPE, type);
            return;
        }
        addEntity(entities, bySurface, segment, text, surface, type, start, end, local);
    }

    private void mapRelation(Map<String, Object> item, int lineNumber, String raw,
                             TextSegment segment, String text, int base,
                             List<PreEntity> entities, Map<String, PreEntity> bySurface,
                             Set<String> spans, List<RelationCandidate> relations,
                             Set<String> relationKeys, List<QuarantinedLine> quarantined) {
        String subjectForm = Json.string(item, "subjectForm", "").strip();
        String objectForm = Json.string(item, "objectForm", "").strip();
        String quote = Json.string(item, "evidenceQuote", "").strip();
        String rawPredicate = Json.string(item, "predicate", "").strip();
        if (subjectForm.isEmpty() || objectForm.isEmpty()) {
            quarantine(quarantined, lineNumber, raw, JsonlFailureReason.EMPTY_FIELD, "missing endpoint");
            return;
        }
        if (!ALLOWED_PREDICATES.contains(rawPredicate)) {
            quarantine(quarantined, lineNumber, raw, JsonlFailureReason.UNKNOWN_PREDICATE, rawPredicate);
            return;
        }
        String predicate = normalizePredicate(rawPredicate);
        if (subjectForm.equalsIgnoreCase(objectForm)) {
            quarantine(quarantined, lineNumber, raw, JsonlFailureReason.SELF_RELATION, subjectForm);
            return;
        }
        PreEntity subject = resolve(subjectForm, quote, text, base, segment,
                entities, bySurface, spans);
        PreEntity object = resolve(objectForm, quote, text, base, segment,
                entities, bySurface, spans);
        if (subject == null || object == null) {
            quarantine(quarantined, lineNumber, raw, JsonlFailureReason.UNANCHORED,
                    subject == null ? subjectForm : objectForm);
            return;
        }
        if (subject.mentionId().equals(object.mentionId())) {
            quarantine(quarantined, lineNumber, raw, JsonlFailureReason.SELF_RELATION, subjectForm);
            return;
        }
        int evidenceStart;
        int evidenceEnd;
        int quoteAt = quote.isEmpty() ? -1 : text.indexOf(quote);
        if (quoteAt >= 0) {
            evidenceStart = base + quoteAt;
            evidenceEnd = evidenceStart + quote.length();
        } else {
            evidenceStart = Math.min(subject.charStart(), object.charStart());
            evidenceEnd = Math.max(subject.charEnd(), object.charEnd());
        }
        String key = subject.mentionId() + "|" + predicate + "|" + object.mentionId()
                + "|" + evidenceStart + "|" + evidenceEnd;
        if (!relationKeys.add(key)) {
            quarantine(quarantined, lineNumber, raw, JsonlFailureReason.DUPLICATE, key);
            return;
        }
        relations.add(new RelationCandidate(subject.mentionId(), predicate, object.mentionId(),
                segment.chunkId(), evidenceStart, evidenceEnd));
    }

    private static void quarantine(List<QuarantinedLine> quarantined, int lineNumber, String raw,
                                   JsonlFailureReason reason, String detail) {
        quarantined.add(new QuarantinedLine(lineNumber, raw, reason.name(), detail,
                reason.category().name()));
    }

    private static boolean looksLikeLegacyObject(String content) {
        if (content == null) {
            return false;
        }
        boolean legacyKeys = content.contains("\"preEntities\"") && content.contains("\"relations\"");
        boolean jsonlShaped = content.contains("\"type\":\"entity\"")
                || content.contains("\"type\":\"relation\"");
        // Fallback is intentional only when the response is not JSONL-shaped but
        // carries both legacy top-level keys. A malformed legacy object still
        // reaches parseLegacyObject so its specific parse error is captured.
        return legacyKeys && !jsonlShaped;
    }

    private ExtractionResult parseLegacyObject(TextSegment segment, ExtractionContext context,
                                               LlmCallResult call, String content,
                                               LlmProvenance provenance) {
        String parserInput = null;
        Map<String, Object> raw;
        try {
            parserInput = Json.extractObject(content);
            raw = Json.parseObject(parserInput);
        } catch (RuntimeException parseFailure) {
            captureParseFailure(segment, context, call, content, parserInput, parseFailure);
            throwIncompleteOrParseFailure(call, parseFailure);
            return ExtractionResult.empty();
        }

        List<PreEntity> entities = new ArrayList<>();
        Map<String, PreEntity> bySurface = new LinkedHashMap<>();
        Set<String> spans = new LinkedHashSet<>();
        List<QuarantinedLine> quarantined = new ArrayList<>();
        for (Map<String, Object> item : Json.objectList(raw, "preEntities")) {
            mapEntity(item, 0, JsonlWriter.write(item), segment, context,
                    segment.text() == null ? "" : segment.text(), segment.charOffsetStart(),
                    entities, bySurface, spans, quarantined);
        }
        List<RelationCandidate> relations = new ArrayList<>();
        Set<String> relationKeys = new LinkedHashSet<>();
        for (Map<String, Object> item : Json.objectList(raw, "relations")) {
            mapRelation(item, 0, JsonlWriter.write(item), segment,
                    segment.text() == null ? "" : segment.text(), segment.charOffsetStart(),
                    entities, bySurface, spans, relations, relationKeys, quarantined);
        }
        if (!quarantined.isEmpty()) {
            captureQuarantine(segment, context, call, quarantined);
        }
        ExtractionIntegrity integrity = integrity(quarantined);
        if (call.usage().isIncomplete() && integrity == ExtractionIntegrity.CLEAN) {
            integrity = ExtractionIntegrity.TRUNCATED;
        }
        return new ExtractionResult(List.copyOf(entities), List.copyOf(relations), call.usage(),
                List.copyOf(quarantined), integrity, provenance);
    }

    private void throwIncompleteOrParseFailure(LlmCallResult call, RuntimeException failure) {
        if (call.usage().isIncomplete()) {
            throw new LlmIncompleteOutputException(call.usage(), call.rawBody().length(),
                    sha256(call.rawBody()));
        }
        throw failure;
    }

    private static String summarizeRejections(List<JsonlRejectedLine> rejected) {
        if (rejected.isEmpty()) {
            return "no records";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(3, rejected.size()); i++) {
            JsonlRejectedLine line = rejected.get(i);
            if (i > 0) {
                out.append(", ");
            }
            out.append("line ").append(line.lineNumber()).append(' ').append(line.reason());
        }
        if (rejected.size() > 3) {
            out.append(", …");
        }
        return out.toString();
    }


    private PreEntity resolve(String surface, String quote, String text, int base, TextSegment segment,
                              List<PreEntity> entities, Map<String, PreEntity> bySurface,
                              Set<String> spans) {
        PreEntity existing = bySurface.get(surface.toLowerCase(Locale.ROOT));
        if (existing != null) {
            return existing;
        }
        int local = -1;
        if (!quote.isEmpty()) {
            int quoteAt = text.indexOf(quote);
            if (quoteAt >= 0) {
                int inside = text.indexOf(surface, quoteAt);
                if (inside >= 0 && inside < quoteAt + quote.length()) {
                    local = inside;
                }
            }
        }
        if (local < 0) {
            local = locate(text, surface, -1);
        }
        if (local < 0) {
            return null;
        }
        surface = text.substring(local, local + surface.length());
        return addEntity(entities, bySurface, segment, text, surface, "UNKNOWN",
                base + local, base + local + surface.length(), local);
    }

    private PreEntity addEntity(List<PreEntity> entities, Map<String, PreEntity> bySurface,
                                TextSegment segment, String text, String surface, String type,
                                int start, int end, int local) {
        String mentionId = UUID.nameUUIDFromBytes(
                (segment.chunkId() + "|" + start + "|" + end).getBytes(StandardCharsets.UTF_8)).toString();
        PreEntity entity = new PreEntity(mentionId, surface, type, start, end,
                sentenceContaining(text, local));
        entities.add(entity);
        bySurface.putIfAbsent(surface.toLowerCase(Locale.ROOT), entity);
        return entity;
    }

    private static int locate(String text, String surface, int hint) {
        if (surface == null || surface.isEmpty()) {
            return -1;
        }
        if (hint >= 0 && hint + surface.length() <= text.length()
                && text.regionMatches(hint, surface, 0, surface.length())) {
            return hint;
        }
        List<Integer> occurrences = occurrences(text, surface, false);
        if (occurrences.isEmpty()) {
            occurrences = occurrences(text, surface, true);
        }
        if (occurrences.isEmpty()) {
            return -1;
        }
        if (hint < 0) {
            return occurrences.get(0);
        }
        int target = hint;
        return occurrences.stream()
                .min(Comparator.comparingInt(index -> Math.abs(index - target)))
                .orElse(occurrences.get(0));
    }

    private static List<Integer> occurrences(String text, String surface, boolean ignoreCase) {
        String haystack = ignoreCase ? text.toLowerCase(Locale.ROOT) : text;
        String needle = ignoreCase ? surface.toLowerCase(Locale.ROOT) : surface;
        List<Integer> out = new ArrayList<>();
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) {
                break;
            }
            out.add(index);
            from = index + 1;
        }
        return out;
    }

    private LlmCallResult call(String prompt, String text) {
        String body = "{\"model\":" + Json.quote(model)
                + ",\"temperature\":0,\"max_tokens\":" + maxTokens
                + ",\"messages\":[{\"role\":\"system\",\"content\":" + Json.quote(prompt)
                + "},{\"role\":\"user\",\"content\":" + Json.quote("TEXT:\n" + text) + "}]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(chatCompletionsUrl()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            String responseBody;
            try (InputStream responseStream = response.body()) {
                responseBody = new String(readBounded(responseStream, MAX_RESPONSE_BYTES),
                        StandardCharsets.UTF_8);
            }
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("LLM extraction returned HTTP " + response.statusCode()
                        + ": " + truncate(responseBody));
            }
            Map<String, Object> envelope = Json.parseObject(responseBody);
            List<Map<String, Object>> choices = Json.objectList(envelope, "choices");
            if (choices.isEmpty()) {
                throw new IllegalStateException("LLM extraction response has no choices");
            }
            Map<String, Object> choice = choices.get(0);
            String finishReason = Json.string(choice, "finish_reason", "unknown");
            Map<String, Object> usage = Json.object(envelope, "usage");
            LlmUsage llmUsage = new LlmUsage(
                    finishReason,
                    Json.integer(usage, "prompt_tokens", -1),
                    Json.integer(usage, "completion_tokens", -1),
                    Json.integer(usage, "total_tokens", -1));
            String content = Json.string(Json.object(choice, "message"), "content", "");
            return new LlmCallResult(content, llmUsage, responseBody);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM extraction interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("LLM extraction request failed: " + endpoint, e);
        }
    }

    private static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maxBytes) {
                throw new IllegalStateException(
                        "LLM extraction response exceeds " + maxBytes + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void captureQuarantine(TextSegment segment, ExtractionContext context,
                                   LlmCallResult call, List<QuarantinedLine> quarantined) {
        if (captureDir == null || quarantined.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(captureDir);
            List<Map<String, Object>> records = new ArrayList<>(quarantined.size());
            for (QuarantinedLine line : quarantined) {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("timestamp", Instant.now().toString());
                event.put("tenant_id", context == null ? null : context.tenantId());
                event.put("source_version_id", context == null ? null : context.sourceVersionId());
                event.put("document_id", context == null ? null : context.documentId());
                event.put("chunk_id", segment == null ? null : segment.chunkId());
                event.put("finish_reason", call.usage().finishReason());
                event.put("line", line.lineNumber());
                event.put("reason", line.reasonCode());
                event.put("category", line.category());
                event.put("detail", line.detail());
                event.put("raw", line.raw());
                records.add(event);
            }
            Path target = captureDir.resolve("quarantine-" + UUID.randomUUID() + ".jsonl");
            Files.writeString(target, JsonlWriter.writeAll(records), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(target,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystems still rely on the capture directory ACL.
            }
        } catch (Exception ignored) {
            // Capture must never mask the extraction result.
        }
    }

    private void captureParseFailure(TextSegment segment, ExtractionContext context,
                                     LlmCallResult call, String content, String parserInput,
                                     RuntimeException failure) {        if (captureDir == null) {
            return;
        }
        try {
            Files.createDirectories(captureDir);
            String raw = call.rawBody();
            String input = parserInput == null ? "" : parserInput;
            StringBuilder json = new StringBuilder(256 + raw.length() + content.length());
            json.append('{');
            appendField(json, "timestamp", Instant.now().toString()).append(',');
            appendField(json, "tenant_id", context == null ? null : context.tenantId()).append(',');
            appendField(json, "source_version_id", context == null ? null : context.sourceVersionId()).append(',');
            appendField(json, "document_id", context == null ? null : context.documentId()).append(',');
            appendField(json, "chunk_id", segment == null ? null : segment.chunkId()).append(',');
            appendField(json, "finish_reason", call.usage().finishReason()).append(',');
            json.append("\"prompt_tokens\":").append(call.usage().promptTokens()).append(',');
            json.append("\"completion_tokens\":").append(call.usage().completionTokens()).append(',');
            json.append("\"total_tokens\":").append(call.usage().totalTokens()).append(',');
            appendField(json, "raw_response_sha256", sha256(raw)).append(',');
            json.append("\"raw_response_chars\":").append(raw.length()).append(',');
            appendField(json, "content_sha256", sha256(content)).append(',');
            json.append("\"content_chars\":").append(content.length()).append(',');
            appendField(json, "parser_input_sha256", sha256(input)).append(',');
            json.append("\"parser_input_chars\":").append(input.length()).append(',');
            appendField(json, "parser_exception", failure.toString()).append(',');
            appendField(json, "raw_response", raw).append(',');
            appendField(json, "content", content).append(',');
            appendField(json, "parser_input", input);
            json.append('}');
            Path target = captureDir.resolve("parse-failure-" + UUID.randomUUID() + ".json");
            Files.writeString(target, json.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(target,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystems still rely on the capture directory ACL.
            }
        } catch (Exception ignored) {
            // Capture must never mask the parser failure.
        }
    }

    private static StringBuilder appendField(StringBuilder json, String key, String value) {
        return json.append(Json.quote(key)).append(':')
                .append(value == null ? "null" : Json.quote(value));
    }

    private record LlmCallResult(String content, LlmUsage usage, String rawBody) {
    }

    private String chatCompletionsUrl() {
        return endpoint.endsWith("/v1")
                ? endpoint + "/chat/completions"
                : endpoint + "/v1/chat/completions";
    }

    private String buildPrompt(ExtractionContext context) {
        String types = context != null && !context.allowedEntityTypes().isEmpty()
                ? String.join(", ", context.allowedEntityTypes())
                : DEFAULT_TYPES;
        return promptTemplate.replace("{allowed_types}", types)
                .replace("{allowed_predicates}", DEFAULT_PREDICATES)
                .replace("{relation_contract}", RELATION_CONTRACT);
    }

    private static String normalizePredicate(String predicate) {
        String normalized = predicate == null ? "" : predicate.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isEmpty() ? "related_to" : normalized;
    }

    private static String sentenceContaining(String text, int offset) {
        int previous = text.lastIndexOf(". ", offset);
        int start = previous < 0 ? 0 : previous + 2;
        int end = text.indexOf(". ", offset);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(start, Math.min(end + 1, text.length())).strip();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "...";
    }

    private static String normalizePromptVersion(String version) {
        String value = version == null || version.isBlank() ? PROMPT_VERSION : version.strip();
        if (!value.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("invalid prompt version: " + value);
        }
        return value;
    }

    private static String loadPrompt(String version) {
        String resource = "/prompts/" + version + ".txt";
        try (var stream = LlmExtractionAdapter.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing prompt resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load " + resource, e);
        }
    }
}
