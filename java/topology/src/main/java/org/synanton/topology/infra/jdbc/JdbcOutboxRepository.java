package org.synanton.topology.infra.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutboxRepository {

    private final JdbcTemplate jdbc;

    public JdbcOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String eventType, String payloadJson) {
        // Use SQL CAST so the driver passes a plain String and Postgres coerces to jsonb.
        jdbc.update(
                "INSERT INTO topology.topology_outbox (event_type, payload) VALUES (?, CAST(? AS jsonb))",
                eventType,
                payloadJson
        );
    }
}
