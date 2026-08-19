package org.synanton.syntology.domain.model;

public record RelationType(
        String uri,
        String label,
        String domain,
        String range
) {
}
