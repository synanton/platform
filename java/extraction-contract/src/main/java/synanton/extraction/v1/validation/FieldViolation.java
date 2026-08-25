package synanton.extraction.v1.validation;

/**
 * One field-level validation failure on a {@code synanton.extraction.v1} request.
 *
 * @param field   dotted path to the offending field, e.g. {@code items[2].source.sha256}
 * @param rule    the rule identifier that failed, e.g. {@code string.pattern}; stable enough for
 *                metrics dimensions
 * @param message operator- and developer-facing description
 */
public record FieldViolation(String field, String rule, String message) {

    /** A required field was absent or blank. */
    public static FieldViolation required(String field) {
        return new FieldViolation(field, "required", field + " is required");
    }

    @Override
    public String toString() {
        return field + ": " + message + " (" + rule + ")";
    }
}
