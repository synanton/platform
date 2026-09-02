package org.synanton.annotations.infra.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.synanton.annotations.domain.model.AnnotationDefinition;
import org.synanton.annotations.domain.repository.AnnotationDefinitionRepository;

import java.util.Optional;

@Repository
public class JdbcAnnotationDefinitionRepository implements AnnotationDefinitionRepository {

    private final JdbcTemplate jdbc;

    public JdbcAnnotationDefinitionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AnnotationDefinition insert(AnnotationDefinition definition) {
        jdbc.update(
                """
                INSERT INTO annotations.annotation_definitions
                    (definition_id, namespace, name, annotation_type, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                definition.definitionId(), definition.namespace(), definition.name(),
                definition.annotationType(), java.sql.Timestamp.from(definition.createdAt())
        );
        return definition;
    }

    @Override
    public Optional<AnnotationDefinition> findById(String definitionId) {
        var rows = jdbc.query(
                """
                SELECT definition_id, namespace, name, annotation_type, created_at
                FROM annotations.annotation_definitions WHERE definition_id = ?
                """,
                (rs, rowNum) -> new AnnotationDefinition(
                        rs.getString("definition_id"), rs.getString("namespace"), rs.getString("name"),
                        rs.getString("annotation_type"), rs.getTimestamp("created_at").toInstant()
                ),
                definitionId
        );
        return rows.stream().findFirst();
    }
}
