package org.synanton.synquest.search;

import java.util.List;

/**
 * Canonical entity projection (Neo4j :Entity → Lucene). Lucene is only a
 * projection; resolution must verify the entity in Neo4j before linking.
 * {@code embedding} is optional (label/aliases/description embedding).
 */
public record EntitySearchDocument(
        String entityId,
        String label,
        List<String> aliases,
        String type,
        String description,
        float[] embedding) {

    public EntitySearchDocument {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public EntitySearchDocument(String entityId, String label, List<String> aliases,
                                String type, String description) {
        this(entityId, label, aliases, type, description, null);
    }
}
