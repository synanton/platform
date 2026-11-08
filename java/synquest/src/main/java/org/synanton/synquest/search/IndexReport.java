package org.synanton.synquest.search;

/** Engine-neutral projection report surfaced by index stats. */
public record IndexReport(
        String embeddingModel,
        int embeddingDim,
        boolean truncated,
        int docs,
        int vectorDocs,
        int dimMismatches,
        int missingVectors,
        int sectionDocs) {}
