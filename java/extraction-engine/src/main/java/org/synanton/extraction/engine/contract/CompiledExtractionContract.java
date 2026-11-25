package org.synanton.extraction.engine.contract;

import java.util.List;
import java.util.Set;

/** P0-compiled extraction contract used to hydrate the Gateway extraction prompt. */
public record CompiledExtractionContract(
        String releaseId,
        String releaseDigest,
        String compilerVersion,
        String exportProfile,
        String schemaVersion,
        String specDigest,
        String templateDigest,
        String compilationId,
        String contractDigest,
        List<EntityType> entities,
        List<RelationType> relations
) {
    public CompiledExtractionContract {
        entities = List.copyOf(entities);
        relations = List.copyOf(relations);
    }

    public record EntityType(String id, String label) {}

    public record RelationType(
            String id,
            String label,
            List<String> subjects,
            List<String> objects,
            String status
    ) {
        public RelationType {
            subjects = List.copyOf(subjects);
            objects = List.copyOf(objects);
        }
    }

    public Set<String> entityIds() {
        return entities.stream().map(EntityType::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Set<String> predicateIds() {
        return relations.stream().map(RelationType::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public String allowedTypes() {
        return String.join(", ", entities.stream().map(EntityType::id).toList());
    }

    public String allowedPredicates() {
        return String.join(", ", relations.stream().map(RelationType::id).toList());
    }

    public String relationContract() {
        StringBuilder out = new StringBuilder();
        for (RelationType relation : relations) {
            for (String subject : relation.subjects()) {
                for (String object : relation.objects()) {
                    out.append(subject).append(" --").append(relation.id())
                        .append("--> ").append(object).append('\n');
                }
            }
        }
        return out.toString().strip();
    }
}
