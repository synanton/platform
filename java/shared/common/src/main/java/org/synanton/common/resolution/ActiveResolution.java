package org.synanton.common.resolution;

/** An authoritative ACTIVE resolution: canonical endpoint + current graph type. */
public record ActiveResolution(
        String mentionId,
        String canonicalEntityId,
        String canonicalEntityType) {}
