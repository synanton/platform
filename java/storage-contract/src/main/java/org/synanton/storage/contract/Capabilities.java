package org.synanton.storage.contract;

/**
 * Dotted capability names shared by conformance matrices, startup validation, and
 * operator-facing errors. One vocabulary across §9.2 (flags), §9.3 (evidence), and
 * §8.2 (provider selection).
 */
public final class Capabilities {
    private Capabilities() {}

    // SynvaultStore (Knowledge 1.25 persistence)
    public static final String SYNVAULT_REVISION = "synvault.revision";
    public static final String SYNVAULT_DELETE = "synvault.delete";
    public static final String SYNVAULT_DOCUMENT = "synvault.document";
    public static final String SYNVAULT_CHUNKS = "synvault.chunks";
    public static final String SYNVAULT_PROVENANCE = "synvault.provenance";
    public static final String SYNVAULT_PAGINATION = "synvault.pagination";
    public static final String SYNVAULT_OCC = "synvault.occ";

    // SynquestEngine / Writer / Admin (Search 1.31 retrieval + projection lifecycle)
    public static final String SYNQUEST_LEXICAL = "synquest.lexical";
    public static final String SYNQUEST_VECTOR = "synquest.vector";
    public static final String SYNQUEST_HYBRID = "synquest.hybrid";
    public static final String SYNQUEST_FILTERS = "synquest.filters";
    public static final String SYNQUEST_HIGHLIGHTS = "synquest.highlights";
    public static final String SYNQUEST_ELIGIBILITY = "synquest.eligibility";
    public static final String SYNQUEST_TEMPORAL_REJECTION = "synquest.temporal-rejection";
    public static final String SYNQUEST_ORDERING = "synquest.ordering";
    public static final String SYNQUEST_GENERATION_DELETE = "synquest.generation-delete";
}
