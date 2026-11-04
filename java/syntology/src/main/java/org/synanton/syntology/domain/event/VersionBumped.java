package org.synanton.syntology.domain.event;

import java.util.UUID;

/** Payload of a {@code VersionBumped} event: a new ontology version became current. */
public record VersionBumped(UUID versionId, String version, VersionBumpSource source) {
}
