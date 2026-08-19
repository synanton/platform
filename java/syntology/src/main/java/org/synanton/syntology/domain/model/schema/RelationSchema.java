package org.synanton.syntology.domain.model.schema;

public record RelationSchema(
        String id,
        String label,
        String domain,
        String range
) {
    public RelationSchema merge(RelationSchema other) {
        if (other == null) {
            return this;
        }
        return new RelationSchema(
                this.id,
                firstNonBlank(other.label, this.label),
                firstNonBlank(other.domain, this.domain),
                firstNonBlank(other.range, this.range)
        );
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }
}
