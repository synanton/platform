package org.synanton.syntology.domain.model.schema;

public record OntologyMeta(
        String id,
        String namespace,
        String prefix,
        String label,
        String description
) {
    public OntologyMeta merge(OntologyMeta other) {
        if (other == null) {
            return this;
        }
        if (this.id == null && this.namespace == null && this.prefix == null) {
            return other;
        }
        return new OntologyMeta(
                firstNonBlank(this.id, other.id),
                firstNonBlank(this.namespace, other.namespace),
                firstNonBlank(this.prefix, other.prefix),
                firstNonBlank(this.label, other.label),
                firstNonBlank(this.description, other.description)
        );
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }
}
