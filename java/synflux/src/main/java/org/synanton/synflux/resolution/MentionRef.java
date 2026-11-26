package org.synanton.synflux.resolution;

/** A mention is a source-level identity; it is not a canonical concept. */
public record MentionRef(String mentionId, String surfaceForm, String proposedType) {
    public MentionRef {
        if (mentionId == null || mentionId.isBlank()) {
            throw new IllegalArgumentException("mentionId must not be blank");
        }
        if (surfaceForm == null || surfaceForm.isBlank()) {
            throw new IllegalArgumentException("surfaceForm must not be blank");
        }
    }
}
