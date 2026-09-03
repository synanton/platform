package org.synanton.annotations.domain.resolutor;

/**
 * One target that needs re-annotation (design §49 output shape). {@code fromVersion}/
 * {@code toVersion} are populated only for the definition that actually changed; for a
 * transitively-affected downstream definition they are {@code null} - its own inputs
 * changed, but it is still on its current published version, not moving between two.
 */
public record RecalculationWorkItem(
        String targetType,
        String targetId,
        String definitionId,
        Integer fromVersion,
        Integer toVersion
) {
}
