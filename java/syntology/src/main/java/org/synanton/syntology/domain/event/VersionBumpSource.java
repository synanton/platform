package org.synanton.syntology.domain.event;

/**
 * Origin of a {@link VersionBumped} event. HCL bumps come from a compiled schema
 * bundle; ONTOLOGY bumps come from a manual ontology edit; UNKNOWN is the
 * explicit name for a source that was not classified.
 */
public enum VersionBumpSource {
    HCL,
    ONTOLOGY,
    UNKNOWN
}
