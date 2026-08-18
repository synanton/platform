package org.synanton.common.validation.constraints;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@NotBlank
@Pattern(regexp = "^[a-zA-Z0-9_-]{1,64}$")
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantId {
    String message() default "must be a tenant id (1-64 alphanumeric, underscore or hyphen)";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
