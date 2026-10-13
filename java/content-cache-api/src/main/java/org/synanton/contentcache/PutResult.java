package org.synanton.contentcache;

import java.util.Objects;

/** Outcome of a {@link ContentStore#put} call. */
public record PutResult(ContentReference reference, boolean dedupHit) {
    public PutResult {
        Objects.requireNonNull(reference, "reference");
    }
}
