package org.synanton.common.validation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationProblem(
        String type,
        String title,
        int status,
        @JsonProperty("field_errors") List<FieldError> fieldErrors
) {
    public record FieldError(String field, String error) {}
}
