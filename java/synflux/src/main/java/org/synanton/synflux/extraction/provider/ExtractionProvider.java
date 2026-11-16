package org.synanton.synflux.extraction.provider;

import java.util.Locale;

/** Canonical extraction adapter identifiers. The serving backend may change. */
public enum ExtractionProvider {
    REGEX("regex"),
    LLM("llm");

    private final String canonical;

    ExtractionProvider(String canonical) {
        this.canonical = canonical;
    }

    public String canonical() {
        return canonical;
    }

    public static ExtractionProvider parse(String raw) {
        String value = raw == null ? "" : raw.strip();
        return switch (value) {
            case "regex" -> REGEX;
            case "llm", "vllm" -> LLM;
            default -> throw new ProviderConfigInvalidException(
                    "Unsupported extraction provider '" + raw + "' (want regex|llm|vllm)");
        };
    }

    public static ExtractionProvider parseOrDefault(String raw) {
        return raw == null || raw.isBlank() ? REGEX : parse(raw);
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
