package org.synanton.syntology.domain.service;

import org.springframework.stereotype.Service;
import org.synanton.syntology.domain.model.EntityType;
import org.synanton.syntology.domain.model.RelationType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class OntologyLintService {

    public record Violation(String focusNode, String resultPath, String sourceShape, String resultMessage) {}

    public Map<String, Object> lint(List<EntityType> entities, List<RelationType> relations) {
        List<Violation> violations = new ArrayList<>();
        List<String> labels = entities.stream().map(EntityType::label).toList();
        long unique = labels.stream().distinct().count();
        if (unique != labels.size()) {
            violations.add(new Violation(
                    "ontology", "label", "UniqueLabelShape", "Duplicate entity labels detected"));
        }
        List<String> orphans = entities.stream()
                .filter(entity -> entity.superTypes() != null && !entity.superTypes().isEmpty()
                        && entity.superTypes().stream().anyMatch(superType ->
                        entities.stream().noneMatch(other -> superType.equals(other.uri())
                                || superType.equals(other.label()))))
                .map(EntityType::label)
                .toList();
        return Map.of(
                "ok", violations.isEmpty() && orphans.isEmpty(),
                "violations", violations,
                "orphans", orphans,
                "entity_count", entities.size(),
                "relation_count", relations.size()
        );
    }

    public List<Violation> validateWrite(String label) {
        if (label == null || label.isBlank() || label.length() > 200) {
            return List.of(new Violation(
                    "http://example.com/entity/" + label,
                    "http://example.com/predicate/hasName",
                    "http://example.com/shapes/NameShape",
                    "Value must be a string with length between 1 and 200"
            ));
        }
        return List.of();
    }
}
