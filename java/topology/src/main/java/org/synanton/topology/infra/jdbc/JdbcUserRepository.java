package org.synanton.topology.infra.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.synanton.topology.domain.model.UserEntry;
import org.synanton.topology.domain.repository.UserRepository;

import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcUserRepository implements UserRepository {

    private final JdbcTemplate jdbc;

    public JdbcUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UserEntry> findAll() {
        return jdbc.query(
                "SELECT user_id, username, uid, gids FROM topology.users ORDER BY username",
                userMapper()
        );
    }

    @Override
    public Optional<UserEntry> findByUid(int uid) {
        List<UserEntry> results = jdbc.query(
                "SELECT user_id, username, uid, gids FROM topology.users WHERE uid = ?",
                userMapper(), uid
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public void upsert(UUID orgId, String username, int uid, List<Integer> gids) {
        Integer[] gidArray = gids.toArray(new Integer[0]);
        int updated = jdbc.update(
                "UPDATE topology.users SET uid = ?, gids = ? WHERE username = ?",
                uid, gidArray, username);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO topology.users (org_id, username, uid, gids) VALUES (?, ?, ?, ?)",
                    orgId, username, uid, gidArray);
        }
    }

    private RowMapper<UserEntry> userMapper() {
        return (rs, row) -> {
            UUID userId = UUID.fromString(rs.getString("user_id"));
            String username = rs.getString("username");
            int uid = rs.getInt("uid");
            Array gidArr = rs.getArray("gids");
            Object raw = gidArr.getArray();
            List<Integer> gids;
            if (raw instanceof Integer[] intArray) {
                gids = Arrays.stream(intArray).toList();
            } else {
                // H2 returns Object[] with Integer values
                gids = Arrays.stream((Object[]) raw)
                        .map(o -> ((Number) o).intValue())
                        .toList();
            }
            return new UserEntry(userId, username, uid, gids);
        };
    }
}
