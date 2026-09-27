package org.synanton.storage.contract;

import java.util.Objects;
import java.util.Optional;

/**
 * Cursor-based page request for chunk listing (proposal §9.1 pagination).
 * Offset pagination is not permitted: ordering must be stable so invariant 28
 * (query robustness) can be verified.
 *
 * @param limit      page size, 1..1000
 * @param cursor     opaque resume cursor; empty for the first page
 * @param ascending  stable-order direction
 */
public record PageRequest(int limit, Optional<String> cursor, boolean ascending) {

    public static final int MAX_LIMIT = 1000;

    public PageRequest {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be within 1.." + MAX_LIMIT);
        }
        Objects.requireNonNull(cursor, "cursor");
    }

    public static PageRequest first(int limit) {
        return new PageRequest(limit, Optional.empty(), true);
    }

    public static PageRequest after(int limit, String cursor) {
        return new PageRequest(limit, Optional.of(cursor), true);
    }
}
