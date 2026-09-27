package org.synanton.bench.convergence;

/** One ranked hit. Scores are never compared across legs (spaces differ) — only ids. */
public record TopKEntry(String chunkId, double score, int rank) {}
