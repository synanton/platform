package org.synanton.common.resolution;

/** Ontology type compatibility port; automatic resolution requires this to pass. */
public interface TypeCompatibility {
    boolean compatible(String proposedType, String canonicalType, String ontologyVersion);
}
