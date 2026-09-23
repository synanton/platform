package org.synanton.llm;

import java.util.List;

public record RerankRequest(
        String model,
        String query,
        List<String> passages,
        int topN
) {}