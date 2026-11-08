package org.synanton.extraction.engine.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.synanton.extraction.engine.ExtractionContext;
import org.synanton.extraction.engine.ExtractionEngineAdapter;
import org.synanton.extraction.engine.ExtractionResult;
import org.synanton.extraction.engine.PreEntity;
import org.synanton.extraction.engine.RelationCandidate;
import org.synanton.extraction.engine.TextSegment;
import org.synanton.extraction.engine.json.Json;

/**
 * RAKG Phase B adapter: calls an OpenAI-compatible vLLM endpoint and maps the
 * JSON response to the engine-neutral extraction contract.
 *
 * <p>The model is not asked to be authoritative about character offsets. Each
 * returned surface form is anchored in the segment (the requested offset is only
 * a hint), so invalid counts, repeated mentions, and missing endpoints are
 * handled deterministically. Unanchorable forms (typically pronouns) are
 * dropped; resolution remains downstream.
 *
 * <p>Prompt version: {@code prompts/extraction-v1.txt}. The module stays
 * JDK-only so the evaluator CLI can run with the main class output.
 */
public final class LlmExtractionAdapter implements ExtractionEngineAdapter {

    public static final String PROMPT_VERSION = "extraction-v1";
    private static final String PROMPT_RESOURCE = "/prompts/extraction-v1.txt";
    private static final int MAX_TOKENS = 2048;

    static final String DEFAULT_TYPES = String.join(", ",
            "MaintenanceSystem", "Component", "Protocol", "Procedure", "Material", "Organization",
            "Person", "Role", "Product", "Document", "Standard", "Metric", "Measurement",
            "Location", "Time", "Algorithm", "DataStructure", "Concept", "Event", "Problem");

    static final String DEFAULT_PREDICATES = String.join(", ",
            "has_component", "part_of", "tested_every", "replaced_every", "signs", "records",
            "documents", "performed_on", "logs", "verifies", "replaced_at", "requires",
            "repeats_every", "applied_to", "is_a", "used_in", "improves_over", "discussed_in",
            "same_as", "seeks", "visits", "combines", "implements", "uses", "has", "related_to",
            "computes", "measures", "obeys", "matches");

    private final HttpClient client;
    private final String endpoint;
    private final String model;
    private final Duration timeout;
    private final String promptTemplate;

    public LlmExtractionAdapter(String endpoint, String model) {
        this(endpoint, model, Duration.ofSeconds(120));
    }

    public LlmExtractionAdapter(String endpoint, String model, Duration timeout) {
        this(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10)).build(),
                endpoint, model, timeout);
    }

    public LlmExtractionAdapter(HttpClient client, String endpoint, String model, Duration timeout) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("LLM extraction endpoint is required");
        }
        this.client = client;
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.model = model == null || model.isBlank() ? "qwen3-extraction" : model;
        this.timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        this.promptTemplate = loadPrompt();
    }

    @Override
    public ExtractionResult extract(TextSegment segment, ExtractionContext context) {
        String text = segment.text() == null ? "" : segment.text();
        if (text.isBlank()) {
            return ExtractionResult.empty();
        }
        int base = segment.charOffsetStart();
        String content = call(buildPrompt(context), text);
        Map<String, Object> raw = Json.parseObject(Json.extractObject(content));

        List<PreEntity> entities = new ArrayList<>();
        Map<String, PreEntity> bySurface = new LinkedHashMap<>();
        Set<String> spans = new LinkedHashSet<>();
        for (Map<String, Object> item : Json.objectList(raw, "preEntities")) {
            String surface = Json.string(item, "surfaceForm", "").strip();
            if (surface.isEmpty()) {
                continue;
            }
            int hint = Json.integer(item, "charStart", -1);
            int local = locate(text, surface, hint);
            if (local < 0) {
                continue;
            }
            surface = text.substring(local, local + surface.length());
            int start = base + local;
            int end = start + surface.length();
            if (!spans.add(start + ":" + end)) {
                continue;
            }
            String type = Json.string(item, "proposedType", "Concept").strip();
            if (type.isEmpty()) {
                type = "Concept";
            }
            if (!context.allowedEntityTypes().isEmpty()
                    && !context.allowedEntityTypes().contains(type)) {
                continue;
            }
            addEntity(entities, bySurface, segment, text, surface, type, start, end, local);
        }

        List<RelationCandidate> relations = new ArrayList<>();
        Set<String> relationKeys = new LinkedHashSet<>();
        for (Map<String, Object> item : Json.objectList(raw, "relations")) {
            String subjectForm = Json.string(item, "subjectForm", "").strip();
            String objectForm = Json.string(item, "objectForm", "").strip();
            String quote = Json.string(item, "evidenceQuote", "").strip();
            String predicate = normalizePredicate(Json.string(item, "predicate", "related_to"));
            if (subjectForm.isEmpty() || objectForm.isEmpty() || subjectForm.equalsIgnoreCase(objectForm)) {
                continue;
            }
            PreEntity subject = resolve(subjectForm, quote, text, base, segment,
                    entities, bySurface, spans);
            PreEntity object = resolve(objectForm, quote, text, base, segment,
                    entities, bySurface, spans);
            if (subject == null || object == null || subject.mentionId().equals(object.mentionId())) {
                continue;
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
                continue;
            }
            relations.add(new RelationCandidate(subject.mentionId(), predicate, object.mentionId(),
                    segment.chunkId(), evidenceStart, evidenceEnd));
        }
        return new ExtractionResult(List.copyOf(entities), List.copyOf(relations));
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

    private String call(String prompt, String text) {
        String body = "{\"model\":" + Json.quote(model)
                + ",\"temperature\":0,\"max_tokens\":" + MAX_TOKENS
                + ",\"messages\":[{\"role\":\"system\",\"content\":" + Json.quote(prompt)
                + "},{\"role\":\"user\",\"content\":" + Json.quote("TEXT:\n" + text) + "}]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(chatCompletionsUrl()))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("LLM extraction returned HTTP " + response.statusCode()
                        + ": " + truncate(response.body()));
            }
            Map<String, Object> envelope = Json.parseObject(response.body());
            List<Map<String, Object>> choices = Json.objectList(envelope, "choices");
            if (choices.isEmpty()) {
                throw new IllegalStateException("LLM extraction response has no choices");
            }
            String content = Json.string(Json.object(choices.get(0), "message"), "content", "");
            if (content.isBlank()) {
                throw new IllegalStateException("LLM extraction response has empty content");
            }
            return content;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM extraction interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("LLM extraction request failed: " + endpoint, e);
        }
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
                .replace("{allowed_predicates}", DEFAULT_PREDICATES);
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

    private static String loadPrompt() {
        try (var stream = LlmExtractionAdapter.class.getResourceAsStream(PROMPT_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing prompt resource: " + PROMPT_RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load " + PROMPT_RESOURCE, e);
        }
    }
}
