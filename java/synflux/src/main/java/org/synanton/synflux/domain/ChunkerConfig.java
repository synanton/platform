package org.synanton.synflux.domain;

public record ChunkerConfig(
    int maxTokensPerChunk,
    int maxCharactersPerChunk,
    boolean preferHeadingBoundary,
    boolean keepTableAtomic,
    boolean keepListAtomic,
    boolean keepFigureWithCaption,
    boolean fallbackToTokenSplit,
    int minChunkTokens,
    boolean includeSectionPath,
    boolean includeHeadingInContent
) {
    public static ChunkerConfig defaults() {
        return new ChunkerConfig(512, 2000, true, true, true, true, true, 50, true, true);
    }

    public static ChunkerConfig of(int maxTokens) {
        return new ChunkerConfig(maxTokens, maxTokens * 4, true, true, true, true, true, 50, true, true);
    }

    /** Benchmark text policy: heading once, no section-path breadcrumb. */
    public static ChunkerConfig benchmarkV1() {
        return new ChunkerConfig(512, 2000, true, true, true, true, true, 50, false, true);
    }
}
