package org.synanton.llm;

import java.util.List;

public record EmbedRequest(
        String model,
        List<String> inputs
) {}
