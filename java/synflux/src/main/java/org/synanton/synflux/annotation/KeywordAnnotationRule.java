package org.synanton.synflux.annotation;

import org.synanton.synflux.domain.SemanticChunk;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic, auditable keyword/regex producer - the same style as the classification
 * detectors from v1.23 (SsnDetector/PhoneDetector/etc.), generalized to arbitrary
 * annotation types rather than only security classes.
 */
public class KeywordAnnotationRule implements AnnotationRule {

    private final String definitionId;
    private final int definitionVersion;
    private final String annotationType;
    private final String namespace;
    private final String name;
    private final String producer;
    private final String producerVersion;
    private final Pattern pattern;

    public KeywordAnnotationRule(
            String definitionId,
            int definitionVersion,
            String annotationType,
            String namespace,
            String name,
            String producer,
            String producerVersion,
            List<String> keywords
    ) {
        this.definitionId = definitionId;
        this.definitionVersion = definitionVersion;
        this.annotationType = annotationType;
        this.namespace = namespace;
        this.name = name;
        this.producer = producer;
        this.producerVersion = producerVersion;
        String alternation = keywords.stream()
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElseThrow(() -> new IllegalArgumentException("keywords must not be empty"));
        this.pattern = Pattern.compile("(?i)\\b(" + alternation + ")\\b");
    }

    @Override public String definitionId() { return definitionId; }
    @Override public int definitionVersion() { return definitionVersion; }
    @Override public String annotationType() { return annotationType; }
    @Override public String namespace() { return namespace; }
    @Override public String name() { return name; }
    @Override public String producer() { return producer; }
    @Override public String producerVersion() { return producerVersion; }

    @Override
    public Optional<Match> match(SemanticChunk chunk) {
        String content = chunk.content();
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        return pattern.matcher(content).find() ? Optional.of(new Match(name, 1.0)) : Optional.empty();
    }
}
