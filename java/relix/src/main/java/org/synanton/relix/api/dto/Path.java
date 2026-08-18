package org.synanton.relix.api.dto;

import java.util.List;

public record Path(
        List<String> hops,
        double score
) {}
