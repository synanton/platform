package org.synanton.common.validation;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationExceptionHandlerTest {

    @Test
    void shouldReturnRfc7807FieldErrorsOnStrictMode() {
        ValidationExceptionHandler handler =
                new ValidationExceptionHandler(null, new ValidationProperties(true, 65536));
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "searchRequest");
        binding.addError(new FieldError("searchRequest", "query", "must not be blank"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, binding);

        ResponseEntity<ValidationProblem> response = handler.handleMethodArgument(ex);

        ValidationProblem expected = new ValidationProblem(
                ValidationExceptionHandler.TYPE,
                "Validation failed",
                400,
                java.util.List.of(new ValidationProblem.FieldError("query", "must not be blank"))
        );
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(expected);
    }
}
