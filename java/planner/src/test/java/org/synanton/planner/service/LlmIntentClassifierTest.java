package org.synanton.planner.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.llm.*;
import org.synanton.planner.config.PlannerProperties;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

class LlmIntentClassifierTest {

    private PlannerProperties props;

    @BeforeEach
    void setUp() {
        props = new PlannerProperties(
                new PlannerProperties.Relix("http://relix:8084"),
                new PlannerProperties.Labels(50_000, 300),
                new PlannerProperties.Llm(true, List.of(), "http://llm:8000/v1",
                        "test-model", 2000L, 0.0, 150, 42, 1)
        );
    }

    private LlmIntentClassifier classifierWith(String jsonResponse) {
        LlmClient fakeLlm = new LlmClient() {
            @Override
            public CompletionResponse complete(CompletionRequest req) {
                return new CompletionResponse(jsonResponse, 10, 20);
            }
            @Override
            public EmbedResponse embed(EmbedRequest req) { return new EmbedResponse(List.of()); }
        };
        return new LlmIntentClassifier(fakeLlm, props);
    }

    @Test
    void parsesHybridIntent() throws TimeoutException {
        var classifier = classifierWith(
                """
                {"intent":"HYBRID","confidence":0.9,"entity_hints":["Acme Corp"],"relation_hints":["supplies"]}
                """);
        var result = classifier.classify("who supplies Acme Corp?", Set.of("Organization"), List.of("supplies"));
        assertThat(result.intent()).isEqualTo("HYBRID");
        assertThat(result.confidence()).isCloseTo(0.9, within(0.01));
        assertThat(result.entityHints()).containsExactly("Acme Corp");
        assertThat(result.relationHints()).containsExactly("supplies");
        assertThat(result.llmModel()).isEqualTo("test-model");
    }

    @Test
    void parsesRetrievalOnlyIntent() throws TimeoutException {
        var classifier = classifierWith(
                """
                {"intent":"RETRIEVAL_ONLY","confidence":0.85,"entity_hints":[],"relation_hints":[]}
                """);
        var result = classifier.classify("find documents about Q4 budget", Set.of(), List.of());
        assertThat(result.intent()).isEqualTo("RETRIEVAL_ONLY");
    }

    @Test
    void parsesGraphOnlyIntent() throws TimeoutException {
        var classifier = classifierWith(
                """
                {"intent":"GRAPH_ONLY","confidence":0.92,"entity_hints":["Beta Inc"],"relation_hints":[]}
                """);
        var result = classifier.classify("tell me about Beta Inc", Set.of("Organization"), List.of());
        assertThat(result.intent()).isEqualTo("GRAPH_ONLY");
        assertThat(result.entityHints()).containsExactly("Beta Inc");
    }

    @Test
    void handlesJsonEmbeddedInProse() throws TimeoutException {
        // LLMs sometimes add prose before the JSON
        var classifier = classifierWith(
                "Sure, here is the classification: {\"intent\":\"HYBRID\",\"confidence\":0.7,\"entity_hints\":[],\"relation_hints\":[]}");
        var result = classifier.classify("test", Set.of(), List.of());
        assertThat(result.intent()).isEqualTo("HYBRID");
    }

    @Test
    void defaultsUnknownIntentToRetrievalOnly() throws TimeoutException {
        var classifier = classifierWith(
                "{\"intent\":\"UNKNOWN_INTENT\",\"confidence\":0.5,\"entity_hints\":[],\"relation_hints\":[]}");
        var result = classifier.classify("test", Set.of(), List.of());
        assertThat(result.intent()).isEqualTo("RETRIEVAL_ONLY");
    }

    @Test
    void throwsOnMalformedJson() {
        var classifier = classifierWith("this is not json at all");
        assertThatThrownBy(() -> classifier.classify("test", Set.of(), List.of()))
                .isInstanceOf(LlmIntentClassifier.LlmClassificationException.class);
    }

    @Test
    void throwsTimeoutWhenLlmIsSlow() {
        PlannerProperties tightProps = new PlannerProperties(
                new PlannerProperties.Relix("http://relix:8084"),
                new PlannerProperties.Labels(50_000, 300),
                new PlannerProperties.Llm(true, List.of(), "http://llm:8000/v1",
                        "test-model", 50L, 0.0, 150, 42, 1) // 50ms timeout
        );
        LlmClient slowLlm = new LlmClient() {
            @Override
            public CompletionResponse complete(CompletionRequest req) {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new CompletionResponse("{\"intent\":\"HYBRID\",\"confidence\":0.5}", 0, 0);
            }
            @Override
            public EmbedResponse embed(EmbedRequest req) { return new EmbedResponse(List.of()); }
        };
        var classifier = new LlmIntentClassifier(slowLlm, tightProps);
        assertThatThrownBy(() -> classifier.classify("test", Set.of(), List.of()))
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
    }
}
