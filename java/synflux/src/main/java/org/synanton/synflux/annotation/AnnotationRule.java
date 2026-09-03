package org.synanton.synflux.annotation;

import org.synanton.synflux.domain.SemanticChunk;

import java.util.Optional;

/**
 * A deterministic producer of one annotation type (design §6 - "The generation
 * mechanism is separate from the annotation contract"). This first producer is a
 * rule engine; ML/LLM/human producers can implement the same interface later
 * without changing {@code AnnotationStage}.
 */
public interface AnnotationRule {
    String definitionId();
    int definitionVersion();
    String annotationType();
    String namespace();
    String name();
    String producer();
    String producerVersion();

    Optional<Match> match(SemanticChunk chunk);

    record Match(String value, double confidence) {}
}
