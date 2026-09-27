package org.synanton.synvault.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Cursor-based chunk page with stable ordinal ordering (invariant 28).
 *
 * @param items      chunks in ascending ordinal order
 * @param nextCursor resume cursor; empty when this is the last page
 */
public record ChunkPage(List<Chunk> items, Optional<String> nextCursor) {
    public ChunkPage {
        items = items == null ? List.of() : List.copyOf(items);
        Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
