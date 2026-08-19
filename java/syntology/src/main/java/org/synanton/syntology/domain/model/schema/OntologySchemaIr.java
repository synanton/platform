package org.synanton.syntology.domain.model.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record OntologySchemaIr(
        OntologyMeta ontology,
        List<ClassSchema> classes,
        List<RelationSchema> relations
) {
    public OntologySchemaIr {
        classes = classes == null ? List.of() : List.copyOf(classes);
        relations = relations == null ? List.of() : List.copyOf(relations);
    }

    public static OntologySchemaIr empty() {
        return new OntologySchemaIr(new OntologyMeta(null, null, null, null, null), List.of(), List.of());
    }

    public OntologySchemaIr merge(OntologySchemaIr other) {
        if (other == null) {
            return this;
        }
        OntologyMeta meta = this.ontology == null ? other.ontology : this.ontology.merge(other.ontology);
        Map<String, ClassSchema> classById = new LinkedHashMap<>();
        for (ClassSchema classSchema : this.classes) {
            classById.put(classSchema.id(), classSchema);
        }
        for (ClassSchema classSchema : other.classes) {
            classById.merge(classSchema.id(), classSchema, ClassSchema::merge);
        }
        Map<String, RelationSchema> relationById = new LinkedHashMap<>();
        for (RelationSchema relation : this.relations) {
            relationById.put(relation.id(), relation);
        }
        for (RelationSchema relation : other.relations) {
            relationById.merge(relation.id(), relation, RelationSchema::merge);
        }
        return new OntologySchemaIr(
                meta,
                new ArrayList<>(classById.values()),
                new ArrayList<>(relationById.values())
        );
    }
}
