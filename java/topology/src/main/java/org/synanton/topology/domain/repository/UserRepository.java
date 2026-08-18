package org.synanton.topology.domain.repository;

import org.synanton.topology.domain.model.UserEntry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    List<UserEntry> findAll();
    Optional<UserEntry> findByUid(int uid);
    void upsert(UUID orgId, String username, int uid, List<Integer> gids);
}
