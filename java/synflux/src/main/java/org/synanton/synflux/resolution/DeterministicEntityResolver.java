package org.synanton.synflux.resolution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * A8 v1: deterministic exact-label and explicit-alias resolution.
 * No fuzzy, embedding, or fallback assignment is performed. Unresolved,
 * ambiguous, and type-mismatched mentions remain observable outcomes.
 */
public final class DeterministicEntityResolver {
    public static final String STRATEGY_VERSION = "deterministic-resolution-v1";

    private final CanonicalCatalog catalog;
    private final Map<String, List<CanonicalConcept>> byLabel = new HashMap<>();
    private final Map<String, List<CanonicalConcept>> byAlias = new HashMap<>();

    public DeterministicEntityResolver(CanonicalCatalog catalog) {
        this.catalog = catalog;
        for (CanonicalConcept concept : catalog.concepts()) {
            byLabel.computeIfAbsent(ResolutionText.normalize(concept.label()), ignored -> new ArrayList<>())
                .add(concept);
            for (String alias : new LinkedHashSet<>(concept.aliases())) {
                byAlias.computeIfAbsent(ResolutionText.normalize(alias), ignored -> new ArrayList<>())
                    .add(concept);
            }
        }
    }

    public ResolutionResult resolve(MentionRef mention) {
        String normalized = ResolutionText.normalize(mention.surfaceForm());
        if (normalized.isBlank()) {
            return result(mention, normalized, ResolutionStatus.UNRESOLVED, null, null, "NONE",
                "EMPTY_NORMALIZED_SURFACE");
        }

        List<CanonicalConcept> exact = byLabel.getOrDefault(normalized, List.of());
        if (exact.size() > 1) {
            return result(mention, normalized, ResolutionStatus.AMBIGUOUS, null, null, "EXACT_LABEL",
                "MULTIPLE_CANONICAL_LABELS");
        }
        if (exact.size() == 1) {
            // Canonical labels have explicit precedence over aliases. If a label
            // and an alias collide, the canonical label wins deterministically.
            return resolveCandidate(mention, normalized, exact.getFirst(), "EXACT_LABEL");
        }

        List<CanonicalConcept> aliases = byAlias.getOrDefault(normalized, List.of());
        if (aliases.size() > 1) {
            return result(mention, normalized, ResolutionStatus.AMBIGUOUS, null, null, "ALIAS",
                "MULTIPLE_ALIAS_TARGETS");
        }
        if (aliases.size() == 1) {
            return resolveCandidate(mention, normalized, aliases.getFirst(), "ALIAS");
        }

        return result(mention, normalized, ResolutionStatus.UNRESOLVED, null, null, "NONE",
            "NO_EXACT_LABEL_OR_ALIAS");
    }

    public List<ResolutionResult> resolveAll(List<MentionRef> mentions) {
        return mentions.stream().map(this::resolve).toList();
    }

    public CanonicalCatalog catalog() {
        return catalog;
    }

    private ResolutionResult resolveCandidate(MentionRef mention, String normalized,
                                              CanonicalConcept concept, String method) {
        if (mention.proposedType() != null && !mention.proposedType().isBlank()
            && !mention.proposedType().equals(concept.type())) {
            return result(mention, normalized, ResolutionStatus.TYPE_MISMATCH,
                concept.canonicalId(), concept.label(), method,
                "PROPOSED_TYPE_" + mention.proposedType() + "_EXPECTED_" + concept.type());
        }
        return result(mention, normalized, ResolutionStatus.RESOLVED,
            concept.canonicalId(), concept.label(), method, "CANONICAL_MATCH");
    }

    private ResolutionResult result(MentionRef mention, String normalized, ResolutionStatus status,
                                    String canonicalId, String canonicalLabel, String method,
                                    String reason) {
        return new ResolutionResult(mention.mentionId(), normalized, mention.proposedType(), status,
            canonicalId, canonicalLabel, method, reason, STRATEGY_VERSION,
            catalog.version(), catalog.digest());
    }
}
