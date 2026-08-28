package org.synanton.topology.domain.repository;

import org.synanton.topology.domain.model.ClassGrant;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ClassGrantRepository {

    List<ClassGrant> findActiveByTenantAndSubjectKeys(String tenantId, Set<String> subjectKeys);

    List<ClassGrant> findActiveByTenant(String tenantId);
}
