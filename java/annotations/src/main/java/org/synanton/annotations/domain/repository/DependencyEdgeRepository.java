package org.synanton.annotations.domain.repository;

import org.synanton.annotations.domain.model.DependencyEdge;

import java.util.List;

public interface DependencyEdgeRepository {

    DependencyEdge insert(DependencyEdge edge);

    /** All edges currently registered - small enough at this stage to load in full for cycle checks. */
    List<DependencyEdge> findAll();

    List<DependencyEdge> findByFrom(String definitionId, int version);
}
