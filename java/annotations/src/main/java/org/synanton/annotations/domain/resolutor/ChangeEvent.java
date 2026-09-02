package org.synanton.annotations.domain.resolutor;

/**
 * A single input Resolutor reacts to (design §49). {@code fromVersion}/{@code toVersion}
 * are only meaningful for {@link ChangeType#ANNOTATION_DEFINITION_VERSION_PUBLISHED}.
 */
public record ChangeEvent(ChangeType changeType, String definitionId, Integer fromVersion, Integer toVersion) {
}
