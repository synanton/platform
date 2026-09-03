package org.synanton.annotations.domain.resolutor;

/**
 * Categories of change Resolutor may be asked to resolve impact for (design §49).
 * Only {@link #ANNOTATION_DEFINITION_VERSION_PUBLISHED} has an upstream producer wired
 * today (the {@code annotations} service itself); the others are modelled for forward
 * compatibility but {@code ResolutorService} rejects them until their producers exist -
 * see docs/implementation/annotations-analytics-plane/02-recalculation.md work item 1.
 */
public enum ChangeType {
    ANNOTATION_DEFINITION_VERSION_PUBLISHED,
    SOURCE_CHANGED,
    CLASSIFICATION_POLICY_CHANGED,
    EMBEDDING_MODEL_CHANGED
}
