package org.synanton.annotations.domain.repository;

import org.synanton.annotations.domain.model.AnnotationDefinitionVersion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AnnotationDefinitionVersionRepository {

    AnnotationDefinitionVersion insert(AnnotationDefinitionVersion version);

    Optional<AnnotationDefinitionVersion> find(String definitionId, int version);

    List<AnnotationDefinitionVersion> findByDefinitionId(String definitionId);

    /** Overwrites the mutable content fields of a DRAFT/VALIDATED version in place. */
    void updateContent(AnnotationDefinitionVersion version);

    void updateStatus(String definitionId, int version, String status, Instant publishedAt);

    List<AnnotationDefinitionVersion> findAllPublished();
}
