package org.synanton.synt.domain.model;

import java.util.List;
import java.util.Map;

public record EntityType(
        String uri,
        String label,
        List<String> superTypes,
        Map<String, String> properties
) {
}
