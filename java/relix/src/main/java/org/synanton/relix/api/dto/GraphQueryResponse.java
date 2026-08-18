package org.synanton.relix.api.dto;

import java.util.List;

public record GraphQueryResponse(
        List<Entity> entities,
        List<Edge> edges,
        List<Path> paths,
        GraphTrace trace
) {}
