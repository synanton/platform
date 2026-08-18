package org.synanton.planner.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.llm.*;
import org.synanton.planner.config.PlannerProperties;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class ClassifierRouterTest {

    private IntentClassifier heuristicClassifier;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        heuristicClassifier = new IntentClassifier();
        meterRegistry = new SimpleMeterRegistry();
    }

    private ClassifierRouter routerWith(PlannerProperties props, LlmClient llmClient) {
        return new ClassifierRouter(
                new LlmIntentClassifier(llmClient, props),
                heuristicClassifier,
                props,
                meterRegistry);
    }

    private PlannerProperties propsWithLlm(boolean enabled) {
        return new PlannerProperties(
                new PlannerProperties.Relix("http://relix:8084"),
                new PlannerProperties.Labels(50_000, 300),
                new PlannerProperties.Llm(enabled, List.of(), "http://llm:8000/v1",
                        "test-model", 2000L, 0.0, 150, 42, 1)
        );
    }

    private LlmClient happyLlm(String intent) {
        String json = "{\"intent\":\"" + intent + "\",\"confidence\":0.9,\"entity_hints\":[],\"relation_hints\":[]}";
        return new LlmClient() {
            @Override
            public CompletionResponse complete(CompletionRequest req) {
                return new CompletionResponse(json, 10, 20);
            }
            @Override
            public EmbedResponse embed(EmbedRequest req) { return new EmbedResponse(List.of()); }
        };
    }

    // ---- Flag disabled ----

    @Test
    void flagDisabledRoutesToHeuristic() {
        var router = routerWith(propsWithLlm(false), happyLlm("HYBRID"));
        var decision = router.classify("find documents", "demo", Set.of(), List.of());
        assertThat(decision.classifiedBy()).isEqualTo("heuristic");
        assertThat(decision.fallbackReason()).isEqualTo("flag_disabled");
    }

    @Test
    void flagDisabledLlmReceivesZeroCalls() {
        int[] callCount = {0};
        LlmClient countingLlm = new LlmClient() {
            @Override
            public CompletionResponse complete(CompletionRequest req) {
                callCount[0]++;
                return new CompletionResponse("{\"intent\":\"HYBRID\",\"confidence\":0.9}", 0, 0);
            }
            @Override
            public EmbedResponse embed(EmbedRequest req) { return new EmbedResponse(List.of()); }
        };
        var router = routerWith(propsWithLlm(false), countingLlm);
        router.classify("test query", "demo", Set.of(), List.of());
        assertThat(callCount[0]).isZero();
    }

    // ---- Flag enabled / LLM happy path ----

    @Test
    void flagEnabledUsesLlm() {
        var router = routerWith(propsWithLlm(true), happyLlm("HYBRID"));
        var decision = router.classify("who supplies Acme Corp?", "demo", Set.of("Organization"), List.of());
        assertThat(decision.classifiedBy()).isEqualTo("llm");
        assertThat(decision.intent()).isEqualTo("HYBRID");
        assertThat(decision.templateId()).isEqualTo("T1");
        assertThat(decision.fallbackReason()).isNull();
        assertThat(decision.llmModel()).isEqualTo("test-model");
    }

    @Test
    void llmGraphOnlyMapsToT2() {
        var router = routerWith(propsWithLlm(true), happyLlm("GRAPH_ONLY"));
        var decision = router.classify("tell me about Acme Corp", "demo", Set.of(), List.of());
        assertThat(decision.templateId()).isEqualTo("T2");
    }

    @Test
    void llmRetrievalOnlyMapsToT3() {
        var router = routerWith(propsWithLlm(true), happyLlm("RETRIEVAL_ONLY"));
        var decision = router.classify("find procurement documents", "demo", Set.of(), List.of());
        assertThat(decision.templateId()).isEqualTo("T3");
    }

    // ---- Fallback on LLM timeout ----

    @Test
    void llmTimeoutFallsBackToHeuristic() {
        PlannerProperties tightProps = new PlannerProperties(
                new PlannerProperties.Relix("http://relix:8084"),
                new PlannerProperties.Labels(50_000, 300),
                new PlannerProperties.Llm(true, List.of(), "http://llm:8000/v1",
                        "test-model", 50L, 0.0, 150, 42, 1)
        );
        LlmClient slowLlm = new LlmClient() {
            @Override
            public CompletionResponse complete(CompletionRequest req) {
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new CompletionResponse("{\"intent\":\"HYBRID\",\"confidence\":0.5}", 0, 0);
            }
            @Override
            public EmbedResponse embed(EmbedRequest req) { return new EmbedResponse(List.of()); }
        };
        var router = routerWith(tightProps, slowLlm);
        var decision = router.classify("find documents", "demo", Set.of(), List.of());
        assertThat(decision.classifiedBy()).isEqualTo("heuristic");
        assertThat(decision.fallbackReason()).isEqualTo("llm_timeout");
    }

    @Test
    void llmTimeoutIncrementsFallbackCounter() {
        PlannerProperties tightProps = new PlannerProperties(
                new PlannerProperties.Relix("http://relix:8084"),
                new PlannerProperties.Labels(50_000, 300),
                new PlannerProperties.Llm(true, List.of(), "http://llm:8000/v1",
                        "test-model", 50L, 0.0, 150, 42, 1)
        );
        LlmClient slowLlm = new LlmClient() {
            @Override
            public CompletionResponse complete(CompletionRequest req) {
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new CompletionResponse("{}", 0, 0);
            }
            @Override
            public EmbedResponse embed(EmbedRequest req) { return new EmbedResponse(List.of()); }
        };
        var router = routerWith(tightProps, slowLlm);
        router.classify("query 1", "demo", Set.of(), List.of());
        router.classify("query 2", "demo", Set.of(), List.of());

        double count = meterRegistry.counter("planner_llm_fallback_total",
                "reason", "llm_timeout", "tenant", "demo").count();
        assertThat(count).isEqualTo(2.0);
    }

    // ---- Fallback on LLM error ----

    @Test
    void llmBadJsonFallsBackToHeuristic() {
        LlmClient badLlm = new LlmClient() {
            @Override
            public CompletionResponse complete(CompletionRequest req) {
                return new CompletionResponse("NOT JSON AT ALL", 0, 0);
            }
            @Override
            public EmbedResponse embed(EmbedRequest req) { return new EmbedResponse(List.of()); }
        };
        var router = routerWith(propsWithLlm(true), badLlm);
        var decision = router.classify("query", "demo", Set.of(), List.of());
        assertThat(decision.classifiedBy()).isEqualTo("heuristic");
        assertThat(decision.fallbackReason()).isEqualTo("llm_error");
    }

    // ---- Per-tenant allow-list ----

    @Test
    void perTenantAllowListGatesLlm() {
        PlannerProperties tenantProps = new PlannerProperties(
                new PlannerProperties.Relix("http://relix:8084"),
                new PlannerProperties.Labels(50_000, 300),
                new PlannerProperties.Llm(true, List.of("premium-tenant"), "http://llm:8000/v1",
                        "test-model", 2000L, 0.0, 150, 42, 1)
        );
        var router = routerWith(tenantProps, happyLlm("HYBRID"));

        // premium-tenant uses LLM
        var premium = router.classify("query", "premium-tenant", Set.of(), List.of());
        assertThat(premium.classifiedBy()).isEqualTo("llm");

        // other-tenant falls back
        var other = router.classify("query", "other-tenant", Set.of(), List.of());
        assertThat(other.classifiedBy()).isEqualTo("heuristic");
        assertThat(other.fallbackReason()).isEqualTo("flag_disabled");
    }

    // ---- Mapping helpers ----

    @Test
    void intentToTemplateIdMapping() {
        assertThat(ClassifierRouter.intentToTemplateId("RETRIEVAL_ONLY")).isEqualTo("T3");
        assertThat(ClassifierRouter.intentToTemplateId("GRAPH_ONLY")).isEqualTo("T2");
        assertThat(ClassifierRouter.intentToTemplateId("HYBRID")).isEqualTo("T1");
    }

    @Test
    void templateIdToIntentMapping() {
        assertThat(ClassifierRouter.templateIdToIntent("T1")).isEqualTo("HYBRID");
        assertThat(ClassifierRouter.templateIdToIntent("T2")).isEqualTo("GRAPH_ONLY");
        assertThat(ClassifierRouter.templateIdToIntent("T3")).isEqualTo("RETRIEVAL_ONLY");
        assertThat(ClassifierRouter.templateIdToIntent("T4")).isEqualTo("HYBRID");
    }
}
