package org.synanton.syntology.infra.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.synanton.syntology.domain.model.OntologyVersion;
import org.synanton.syntology.domain.port.out.MetadataRepository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class H2MetadataRepository implements MetadataRepository {

    private static final RowMapper<OntologyVersion> ROW_MAPPER = (rs, rowNum) -> new OntologyVersion(
            rs.getObject("version_id", UUID.class),
            rs.getString("tenant_id"),
            rs.getString("version"),
            rs.getString("label"),
            rs.getString("description"),
            rs.getString("graph_uri"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbcTemplate;

    public H2MetadataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<OntologyVersion> findAll(String tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM syntology.ontology_versions WHERE tenant_id = ? ORDER BY created_at DESC",
                ROW_MAPPER,
                tenantId
        );
    }

    @Override
    public Optional<OntologyVersion> findByVersion(String tenantId, String version) {
        if ("active".equalsIgnoreCase(version)) {
            return findActive(tenantId);
        }
        List<OntologyVersion> results = jdbcTemplate.query(
                "SELECT * FROM syntology.ontology_versions WHERE tenant_id = ? AND version = ?",
                ROW_MAPPER,
                tenantId,
                version
        );
        return results.stream().findFirst();
    }

    @Override
    public Optional<OntologyVersion> findActive(String tenantId) {
        List<OntologyVersion> results = jdbcTemplate.query(
                "SELECT * FROM syntology.ontology_versions WHERE tenant_id = ? AND status = 'ACTIVE' ORDER BY created_at DESC LIMIT 1",
                ROW_MAPPER,
                tenantId
        );
        return results.stream().findFirst();
    }

    @Override
    public void insert(OntologyVersion version) {
        jdbcTemplate.update(
                """
                INSERT INTO syntology.ontology_versions
                (version_id, tenant_id, version, label, description, graph_uri, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                version.versionId(),
                version.tenantId(),
                version.version(),
                version.label(),
                version.description(),
                version.graphUri(),
                version.status(),
                Timestamp.from(version.createdAt())
        );
    }

    @Override
    public void deprecateAllActive(String tenantId) {
        jdbcTemplate.update(
                "UPDATE syntology.ontology_versions SET status = 'DEPRECATED' WHERE tenant_id = ? AND status = 'ACTIVE'",
                tenantId
        );
    }

    @Override
    public void updateStatus(UUID versionId, String status) {
        jdbcTemplate.update(
                "UPDATE syntology.ontology_versions SET status = ? WHERE version_id = ?",
                status,
                versionId
        );
    }

    @Override
    public boolean isEmpty() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM syntology.ontology_versions", Integer.class);
        return count == null || count == 0;
    }
}
