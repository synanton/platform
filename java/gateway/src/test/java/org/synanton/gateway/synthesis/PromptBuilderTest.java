package org.synanton.gateway.synthesis;

import org.junit.jupiter.api.Test;
import org.synanton.gateway.config.GatewayProperties;
import org.synanton.gateway.domain.GraphEntity;
import org.synanton.gateway.domain.GraphResult;
import org.synanton.gateway.domain.Hit;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    private static GatewayProperties.Synthesis synthProps(int contextHits, int maxContextTokens) {
        return new GatewayProperties.Synthesis(true, contextHits, maxContextTokens, 8000, 0.3, 150, "model", "http://localhost");
    }

    private static Hit hit(String uri, String snippet) {
        return new Hit("id", 0, 0.5, 0.5, 0.0, false, snippet, uri);
    }

    @Test
    void includesHitsInContext() {
        PromptBuilder builder = new PromptBuilder(synthProps(5, 3000));
        List<Hit> hits = List.of(hit("file:///doc1.txt", "Some text about Acme."));

        PromptBuilder.PromptInput input = builder.build("who is Acme?", hits, null);

        assertThat(input.context()).contains("doc1.txt");
        assertThat(input.context()).contains("Some text about Acme.");
        assertThat(input.query()).isEqualTo("who is Acme?");
    }

    @Test
    void respecsContextHitsLimit() {
        PromptBuilder builder = new PromptBuilder(synthProps(2, 3000));
        List<Hit> hits = List.of(
                hit("file:///a.txt", "A"),
                hit("file:///b.txt", "B"),
                hit("file:///c.txt", "C")
        );

        PromptBuilder.PromptInput input = builder.build("query", hits, null);

        assertThat(input.context()).contains("a.txt");
        assertThat(input.context()).contains("b.txt");
        assertThat(input.context()).doesNotContain("c.txt");
    }

    @Test
    void appendsGraphEntities() {
        PromptBuilder builder = new PromptBuilder(synthProps(5, 3000));
        GraphEntity entity = new GraphEntity("e1", "GlobalTech", "ORG", List.of());
        GraphResult graph = new GraphResult(List.of(entity), List.of(), List.of());

        PromptBuilder.PromptInput input = builder.build("query", List.of(), graph);

        assertThat(input.context()).contains("GlobalTech");
    }

    @Test
    void truncatesContextToTokenBudget() {
        PromptBuilder builder = new PromptBuilder(synthProps(5, 10));  // 10 tokens = 40 chars
        String longSnippet = "X".repeat(1000);
        List<Hit> hits = List.of(hit("file:///big.txt", longSnippet));

        PromptBuilder.PromptInput input = builder.build("query", hits, null);

        assertThat(input.context().length()).isLessThanOrEqualTo(40);
    }

    @Test
    void emptyHitsAndNullGraphProducesEmptyContext() {
        PromptBuilder builder = new PromptBuilder(synthProps(5, 3000));

        PromptBuilder.PromptInput input = builder.build("query", List.of(), null);

        assertThat(input.context()).isBlank();
    }
}
