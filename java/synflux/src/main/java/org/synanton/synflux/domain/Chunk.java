package org.synanton.synflux.domain;

public record Chunk(
    int ordinal,
    String text,
    String sha256
) {}
