package org.synanton.topology.domain.repository;

import org.synanton.topology.domain.model.AclGrant;

import java.util.List;
import java.util.UUID;

public interface AclGrantRepository {
    List<AclGrant> findBySubjectId(UUID subjectId);
    void deleteBySource(String source);
    void insert(UUID orgId, UUID subjectId, String subjectType, String resourcePath, String permission, String source);
}
