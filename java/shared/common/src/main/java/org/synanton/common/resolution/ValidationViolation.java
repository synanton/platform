package org.synanton.common.resolution;

public record ValidationViolation(String code, String message, String path) {}
