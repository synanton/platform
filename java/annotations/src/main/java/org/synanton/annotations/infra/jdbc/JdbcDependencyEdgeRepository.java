package org.synanton.annotations.infra.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.synanton.annotations.domain.model.DependencyEdge;
import org.synanton.annotations.domain.repository.DependencyEdgeRepository;

import java.util.List;

@Repository
public class JdbcDependencyEdgeRepository implements DependencyEdgeRepository {

    private final JdbcTemplate jdbc;

    public JdbcDependencyEdgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public DependencyEdge insert(DependencyEdge edge) {
        jdbc.update(
                """
                INSERT INTO annotations.dependency_edges
                    (from_definition_id, from_version, to_definition_id, to_version, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                edge.fromDefinitionId(), edge.fromVersion(), edge.toDefinitionId(), edge.toVersion(),
                java.sql.Timestamp.from(edge.createdAt())
        );
        return edge;
    }

    @Override
    public List<DependencyEdge> findAll() {
        return jdbc.query(
                "SELECT from_definition_id, from_version, to_definition_id, to_version, created_at " +
                "FROM annotations.dependency_edges",
                (rs, rowNum) -> toEdge(rs)
        );
    }

    @Override
    public List<DependencyEdge> findByFrom(String definitionId, int version) {
        return jdbc.query(
                """
                SELECT from_definition_id, from_version, to_definition_id, to_version, created_at
                FROM annotations.dependency_edges WHERE from_definition_id = ? AND from_version = ?
                """,
                (rs, rowNum) -> toEdge(rs), definitionId, version
        );
    }

    private static DependencyEdge toEdge(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DependencyEdge(
                rs.getString("from_definition_id"), rs.getInt("from_version"),
                rs.getString("to_definition_id"), rs.getInt("to_version"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
