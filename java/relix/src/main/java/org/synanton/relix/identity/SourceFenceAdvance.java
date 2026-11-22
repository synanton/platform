package org.synanton.relix.identity;

/** Result of a monotonic fence advance; created distinguishes the initialization winner. */
public record SourceFenceAdvance(long generation, boolean created) {}
