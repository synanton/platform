package org.synanton.security.idp;

import java.util.List;

/**
 * A user entry loaded from the htpasswd file.
 * Format per line: username:bcrypt(password):uid:gid1,gid2,...
 */
public record UserRecord(
        String username,
        String hashedPassword,
        int uid,
        List<Integer> gids
) {}
