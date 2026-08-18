package org.synanton.topology.domain.model;

import java.util.List;
import java.util.UUID;

public record UserEntry(
        UUID userId,
        String username,
        int uid,
        List<Integer> gids
) {}
