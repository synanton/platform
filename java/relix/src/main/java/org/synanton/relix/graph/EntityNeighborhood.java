package org.synanton.relix.graph;

import java.util.List;

/** Bounded one-hop canonical neighborhood for the entity-rooted graph API. */
public record EntityNeighborhood(
        String entityId,
        String label,
        String type,
        List<Neighbor> neighbors,
        List<Evidence> evidence,
        boolean truncated) {

    public EntityNeighborhood {
        neighbors = neighbors == null ? List.of() : List.copyOf(neighbors);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public record Neighbor(
            String entityId,
            String label,
            String type,
            String relation,
            String direction) {}

    public record Evidence(
            String relation,
            String neighborEntityId,
            String chunkId,
            String sourceVersion,
            int offsetStart,
            int offsetEnd) {}
}
