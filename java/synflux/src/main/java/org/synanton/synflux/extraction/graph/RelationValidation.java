package org.synanton.synflux.extraction.graph;

import java.util.List;

public record RelationValidation(boolean conformant, List<Violation> violations) {

    public record Violation(String code, String message, String path) {}
}
