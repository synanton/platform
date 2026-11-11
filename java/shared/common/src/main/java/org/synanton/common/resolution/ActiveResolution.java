package org.synanton.common.resolution;

import com.fasterxml.jackson.annotation.JsonAlias;

/** An authoritative ACTIVE resolution: canonical endpoint + current graph type. */
public record ActiveResolution(
        @JsonAlias("mention_id") String mentionId,
        @JsonAlias("canonical_entity_id") String canonicalEntityId,
        @JsonAlias("canonical_entity_type") String canonicalEntityType) {}
