package org.synanton.synflux.domain;

import java.util.List;

public record ChunkedDocument(
    ParsedDocument parsed,
    List<SemanticChunk> chunks
) {}
