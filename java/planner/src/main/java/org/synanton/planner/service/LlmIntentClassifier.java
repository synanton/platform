package org.synanton.planner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.synanton.llm.CompletionRequest;
import org.synanton.llm.LlmClient;
import org.synanton.planner.config.PlannerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

@Component
public class LlmIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentClassifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> VALID_INTENTS = Set.of("RETRIEVAL_ONLY", "GRAPH_ONLY", "HYBRID");

    private static final String SYSTEM_PROMPT = """
            You are a search intent classifier for an enterprise knowledge platform.
            Respond ONLY with valid JSON matching the schema below. Do not explain.
            Schema: {"intent": "RETRIEVAL_ONLY | GRAPH_ONLY | HYBRID",
                     "confidence": <0.0-1.0>,
                     "entity_hints": ["<name>", ...],
                     "relation_hints": ["<verb>", ...]}
            """;

    private final LlmClient llmClient;
    private final PlannerProperties props;
    private final ExecutorService executor = Executors.newCachedThreadPool(
            r -> Thread.ofVirtual().name("llm-classify").unstarted(r));

    public LlmIntentClassifier(LlmClient llmClient, PlannerProperties props) {
        this.llmClient = llmClient;
        this.props = props;
    }

    /**
     * Classify the query intent using the LLM.
     *
     * @throws TimeoutException if the LLM exceeds the configured timeout
     * @throws LlmClassificationException if the LLM returns an unparseable or invalid response
     */
    public IntentClassificationResult classify(String query, Set<String> entityTypes,
                                                List<String> relationVerbs) throws TimeoutException {
        String entityTypesCsv = String.join(", ", entityTypes);
        String relationVerbsCsv = String.join(", ", relationVerbs);

        String userMessage = "Query: \"" + query + "\"\n"
                + "Known entity types: " + entityTypesCsv + "\n"
                + "Known relation verbs: " + relationVerbsCsv;

        CompletionRequest req = new CompletionRequest(
                props.llm().model(),
                SYSTEM_PROMPT,
                userMessage,
                props.llm().temperature(),
                props.llm().maxTokens()
        );

        long t0 = System.currentTimeMillis();

        Future<String> future = executor.submit(() -> llmClient.complete(req).text());
        String rawResponse;
        try {
            rawResponse = future.get(props.llm().timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw new LlmClassificationException("LLM call failed: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClassificationException("LLM call interrupted", e);
        }

        long latencyMs = System.currentTimeMillis() - t0;
        return parseResponse(rawResponse, latencyMs);
    }

    private IntentClassificationResult parseResponse(String raw, long latencyMs) {
        try {
            // Extract JSON from response (may have surrounding prose)
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new LlmClassificationException("No JSON object found in LLM response");
            }
            JsonNode node = MAPPER.readTree(raw.substring(start, end + 1));

            String intent = node.path("intent").asText("RETRIEVAL_ONLY").trim().toUpperCase();
            if (!VALID_INTENTS.contains(intent)) {
                log.warn("LLM returned unknown intent '{}', defaulting to RETRIEVAL_ONLY", intent);
                intent = "RETRIEVAL_ONLY";
            }

            double confidence = node.path("confidence").asDouble(0.5);
            confidence = Math.max(0.0, Math.min(1.0, confidence));

            List<String> entityHints = new ArrayList<>();
            node.path("entity_hints").forEach(n -> entityHints.add(n.asText()));

            List<String> relationHints = new ArrayList<>();
            node.path("relation_hints").forEach(n -> relationHints.add(n.asText()));

            return new IntentClassificationResult(intent, confidence, entityHints, relationHints,
                    props.llm().model(), latencyMs);
        } catch (LlmClassificationException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmClassificationException("Failed to parse LLM response: " + e.getMessage(), e);
        }
    }

    public static class LlmClassificationException extends RuntimeException {
        public LlmClassificationException(String msg) { super(msg); }
        public LlmClassificationException(String msg, Throwable cause) { super(msg, cause); }
    }
}
