package org.synanton.relix.service;

import org.synanton.relix.api.dto.*;
import org.synanton.relix.graph.InMemoryConnector;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class EntityLookupExecutor {

    public record Result(List<Entity> entities, int candidateCount) {}

    public Result execute(InMemoryConnector connector, GraphQueryRequest req) {
        String label = req.paramString("label");
        String type = req.paramString("type");
        int limit = req.paramInt("limit", 10);

        if (label == null || label.isBlank()) {
            return new Result(List.of(), 0);
        }

        var candidates = connector.entityIndex().lookup(label, type);
        int candidateCount = candidates.size();
        List<Entity> entities = candidates.stream()
                .limit(limit)
                .map(DtoMapper::toDto)
                .collect(Collectors.toList());
        return new Result(entities, candidateCount);
    }
}
