package org.synanton.contentcache;

import java.util.Objects;

/**
 * Caller-supplied metadata for a {@link ContentStore#put} call (proposal §4.4.2).
 *
 * <p>Content Cache composes the {@link ContentReference} from the payload
 * digest plus these fields; the adapter never composes references itself.
 */
public record ContentMetadata(
        String contentType,
        String ownerId,
        int retentionDays,
        int serializerVersion) {

    public ContentMetadata {
        Objects.requireNonNull(ownerId, "ownerId");
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("retentionDays must be positive");
        }
    }

    public static ContentMetadata of(String contentType, String ownerId) {
        return new ContentMetadata(contentType, ownerId, 90, 1);
    }
}
