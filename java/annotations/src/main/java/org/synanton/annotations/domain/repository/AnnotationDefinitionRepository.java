package org.synanton.annotations.domain.repository;

import org.synanton.annotations.domain.model.AnnotationDefinition;

import java.util.Optional;

public interface AnnotationDefinitionRepository {

    AnnotationDefinition insert(AnnotationDefinition definition);

    Optional<AnnotationDefinition> findById(String definitionId);
}
