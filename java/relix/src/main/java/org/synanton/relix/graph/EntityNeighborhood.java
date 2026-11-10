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
            String direction,
            String canonicalRelationId) {

        public Neighbor(String entityId, String label, String type, String relation,
                        String direction) {
            this(entityId, label, type, relation, direction, null);
        }
    }

    public record Evidence(
            String relation,
            String neighborEntityId,
            String chunkId,
            String sourceVersion,
            int offsetStart,
            int offsetEnd,
            String canonicalRelationId) {

        public Evidence(String relation, String neighborEntityId, String chunkId,
                        String sourceVersion, int offsetStart, int offsetEnd) {
            this(relation, neighborEntityId, chunkId, sourceVersion, offsetStart, offsetEnd, null);
        }
    }
}
