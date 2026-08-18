package org.synanton.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * RFC 7807 Problem Detail for HTTP APIs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetail(
        String type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        Instant timestamp
) {
    public static ProblemDetail of(int status, String title, String detail, String code) {
        return new ProblemDetail(
                "about:blank",
                title,
                status,
                detail,
                null,
                code,
                Instant.now()
        );
    }
}
