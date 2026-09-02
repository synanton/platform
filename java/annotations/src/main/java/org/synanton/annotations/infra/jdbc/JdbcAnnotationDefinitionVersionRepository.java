package org.synanton.annotations.infra.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.synanton.annotations.domain.model.AnnotationDefinitionVersion;
import org.synanton.annotations.domain.repository.AnnotationDefinitionVersionRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAnnotationDefinitionVersionRepository implements AnnotationDefinitionVersionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcAnnotationDefinitionVersionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public AnnotationDefinitionVersion insert(AnnotationDefinitionVersion version) {
        jdbc.update(
                """
                INSERT INTO annotations.annotation_definition_versions
                    (definition_id, version, inputs_json, producer, producer_version,
                     output_type, output_name, status, published_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                version.definitionId(), version.version(), toJson(version.inputs()),
                version.producer(), version.producerVersion(), version.outputType(), version.outputName(),
                version.status(), toTimestamp(version.publishedAt()), toTimestamp(version.createdAt())
        );
        return version;
    }

    @Override
    public Optional<AnnotationDefinitionVersion> find(String definitionId, int version) {
        var rows = jdbc.query(
                """
                SELECT definition_id, version, inputs_json, producer, producer_version,
                       output_type, output_name, status, published_at, created_at
                FROM annotations.annotation_definition_versions
                WHERE definition_id = ? AND version = ?
                """,
                (rs, rowNum) -> toVersion(rs), definitionId, version
        );
        return rows.stream().findFirst();
    }

    @Override
    public List<AnnotationDefinitionVersion> findByDefinitionId(String definitionId) {
        return jdbc.query(
                """
                SELECT definition_id, version, inputs_json, producer, producer_version,
                       output_type, output_name, status, published_at, created_at
                FROM annotations.annotation_definition_versions
                WHERE definition_id = ? ORDER BY version
                """,
                (rs, rowNum) -> toVersion(rs), definitionId
        );
    }

    @Override
    public void updateContent(AnnotationDefinitionVersion version) {
        jdbc.update(
                """
                UPDATE annotations.annotation_definition_versions
                SET inputs_json = ?, producer = ?, producer_version = ?, output_type = ?, output_name = ?
                WHERE definition_id = ? AND version = ?
                """,
                toJson(version.inputs()), version.producer(), version.producerVersion(),
                version.outputType(), version.outputName(), version.definitionId(), version.version()
        );
    }

    @Override
    public void updateStatus(String definitionId, int version, String status, Instant publishedAt) {
        jdbc.update(
                """
                UPDATE annotations.annotation_definition_versions
                SET status = ?, published_at = ?
                WHERE definition_id = ? AND version = ?
                """,
                status, toTimestamp(publishedAt), definitionId, version
        );
    }

    @Override
    public List<AnnotationDefinitionVersion> findAllPublished() {
        return jdbc.query(
                """
                SELECT definition_id, version, inputs_json, producer, producer_version,
                       output_type, output_name, status, published_at, created_at
                FROM annotations.annotation_definition_versions
                WHERE status = 'PUBLISHED' ORDER BY definition_id, version
                """,
                (rs, rowNum) -> toVersion(rs)
        );
    }

    private AnnotationDefinitionVersion toVersion(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp published = rs.getTimestamp("published_at");
        return new AnnotationDefinitionVersion(
                rs.getString("definition_id"), rs.getInt("version"), fromJson(rs.getString("inputs_json")),
                rs.getString("producer"), rs.getString("producer_version"),
                rs.getString("output_type"), rs.getString("output_name"), rs.getString("status"),
                published == null ? null : published.toInstant(), rs.getTimestamp("created_at").toInstant()
        );
    }

    private String toJson(List<String> inputs) {
        try {
            return mapper.writeValueAsString(inputs == null ? List.of() : inputs);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize inputs", e);
        }
    }

    private List<String> fromJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize inputs_json: " + json, e);
        }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
