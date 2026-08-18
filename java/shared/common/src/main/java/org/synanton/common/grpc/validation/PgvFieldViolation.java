package org.synanton.common.grpc.validation;

/**
 * One PGV field violation, mapped onto {@code google.rpc.BadRequest.field_violations}.
 */
public record PgvFieldViolation(String field, String error, String message) {}
