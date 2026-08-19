package org.synanton.syntology.domain.model.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ClassSchema(
        String id,
        String label,
        List<String> superTypes,
        List<PropertySchema> properties
) {
    public ClassSchema {
        superTypes = superTypes == null ? List.of() : List.copyOf(superTypes);
        properties = properties == null ? List.of() : List.copyOf(properties);
    }

    public ClassSchema merge(ClassSchema other) {
        if (other == null) {
            return this;
        }
        List<String> supers = other.superTypes.isEmpty() ? this.superTypes : other.superTypes;
        Map<String, PropertySchema> byName = new LinkedHashMap<>();
        for (PropertySchema property : this.properties) {
            byName.put(property.name(), property);
        }
        for (PropertySchema property : other.properties) {
            byName.put(property.name(), property);
        }
        String mergedLabel = other.label != null && !other.label.isBlank() ? other.label : this.label;
        return new ClassSchema(this.id, mergedLabel, supers, new ArrayList<>(byName.values()));
    }
}
