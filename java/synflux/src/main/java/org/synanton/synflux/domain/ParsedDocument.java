package org.synanton.synflux.domain;

import java.util.Map;

public record ParsedDocument(
    AcquiredDocument acquired,
    String text,
    Map<String, String> metadata
) {}
