package org.synanton.synquest.api;

/**
 * Capability claims for a {@link SynquestEngine} adapter (§9.3 conformance principle:
 * unverified flags report {@code false}). {@code highlights}/{@code explanation} align
 * with the approved Search 1.31 parity contract, not PoC-local definitions.
 */
public record SearchCapabilities(
        boolean lexical,
        boolean vector,
        boolean hybrid,
        boolean filters,
        boolean highlights,
        boolean explanation,
        boolean temporal,
        boolean graph) {

    /** This PoC: lexical/vector/hybrid only; temporal and graph unsupported. */
    public static SearchCapabilities pocBaseline() {
        return new SearchCapabilities(true, true, true, true, true, false, false, false);
    }
}
