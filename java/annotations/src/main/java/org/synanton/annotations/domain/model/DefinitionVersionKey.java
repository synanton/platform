package org.synanton.annotations.domain.model;

/** Identifies one node of the dependency DAG: a specific version of a definition. */
public record DefinitionVersionKey(String definitionId, int version) {
}
