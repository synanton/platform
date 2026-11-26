package org.synanton.synflux.resolution;

import java.util.List;
import java.util.Objects;

/** A canonical concept plus explicit aliases; identity is never inferred from a mention id. */
public record CanonicalConcept(
        String canonicalId,
        String label,
        String type,
        List<String> aliases) {

    public CanonicalConcept {
        if (canonicalId == null || canonicalId.isBlank()) {
            throw new IllegalArgumentException("canonicalId must not be blank");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        if (aliases.stream().anyMatch(alias -> alias == null || alias.isBlank())) {
            throw new IllegalArgumentException("aliases must not contain blank values");
        }
    }

    public CanonicalConcept(String canonicalId, String label, String type) {
        this(canonicalId, label, type, List.of());
    }
}
