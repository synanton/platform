package org.synanton.relix.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** #508 bounded one-hop canonical neighborhood. */
public record EntityNeighborhoodResponse(
        Node entity,
        List<Neighbor> neighbors,
        List<Evidence> evidence,
        boolean truncated) {

    public record Node(
            String id,
            String label,
            String type) {}

    public record Neighbor(
            Node entity,
            String relation,
            String direction) {}

    public record Evidence(
            String relation,
            @JsonProperty("neighbor_entity_id") String neighborEntityId,
            @JsonProperty("chunk_id") String chunkId,
            @JsonProperty("source_version") String sourceVersion,
            @JsonProperty("offset_start") int offsetStart,
            @JsonProperty("offset_end") int offsetEnd) {}
}
