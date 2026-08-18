package org.synanton.topology.infra.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.synanton.topology.domain.model.Connector;

import java.util.List;

@Repository
public class JdbcConnectorRepository {

    private final JdbcTemplate jdbc;

    public JdbcConnectorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Connector> findAll() {
        return jdbc.query(
                "SELECT connector_id, address FROM topology.connectors ORDER BY connector_id",
                (rs, row) -> new Connector(
                        rs.getString("connector_id"),
                        rs.getString("address")
                )
        );
    }
}
