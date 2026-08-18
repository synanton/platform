package org.synanton.gateway.synthesis;

import org.synanton.gateway.config.GatewayProperties;
import org.synanton.gateway.domain.GraphEntity;
import org.synanton.gateway.domain.GraphResult;
import org.synanton.gateway.domain.Hit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

public class PromptBuilder {

    // Rough approximation: 4 chars per token
    private static final int CHARS_PER_TOKEN = 4;

    private final GatewayProperties.Synthesis synthProps;
    private final String systemPromptTemplate;

    public PromptBuilder(GatewayProperties.Synthesis synthProps) {
        this.synthProps = synthProps;
        this.systemPromptTemplate = loadSystemPrompt();
    }

    public PromptInput build(String query, List<Hit> hits, GraphResult graph) {
        StringBuilder context = new StringBuilder();
        int count = 0;

        for (Hit h : hits) {
            if (count >= synthProps.contextHits()) break;
            count++;
            context.append(count).append(". [").append(h.sourceUri() != null ? h.sourceUri() : "unknown").append("]\n");
            if (h.snippet() != null && !h.snippet().isBlank()) {
                context.append(h.snippet()).append("\n");
            }
            context.append("\n");
        }

        if (graph != null && graph.entities() != null && !graph.entities().isEmpty()) {
            context.append("Related entities:\n");
            for (GraphEntity e : graph.entities()) {
                context.append("- ").append(e.label() != null ? e.label() : e.id());
                if (e.type() != null) context.append(" (").append(e.type()).append(")");
                context.append("\n");
            }
        }

        String contextStr = truncate(context.toString(), synthProps.maxContextTokens());
        return new PromptInput(systemPromptTemplate, query, contextStr);
    }

    private String truncate(String text, int maxTokens) {
        int maxChars = maxTokens * CHARS_PER_TOKEN;
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    private String loadSystemPrompt() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("prompts/synthesis-system.mustache")) {
            if (is == null) return defaultSystemPrompt();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            return defaultSystemPrompt();
        }
    }

    private String defaultSystemPrompt() {
        return "You are a precise knowledge assistant. Answer the user's question using only the provided context. "
                + "Cite sources by their number. Be concise (target ≤ 100 words). "
                + "If the context does not contain enough information, say so clearly.";
    }

    public record PromptInput(String systemPrompt, String query, String context) {}
}
