package org.synanton.llm;

import java.util.List;

public record EmbedResponse(
        List<float[]> embeddings
) {}
