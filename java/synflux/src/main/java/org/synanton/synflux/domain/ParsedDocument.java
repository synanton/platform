package org.synanton.synflux.domain;

import synanton.extraction.v1.DocumentPayload;

import java.util.Map;

public record ParsedDocument(
    AcquiredDocument acquired,
    String text,
    Map<String, String> metadata,
    DocumentPayload documentPayload
) {}
