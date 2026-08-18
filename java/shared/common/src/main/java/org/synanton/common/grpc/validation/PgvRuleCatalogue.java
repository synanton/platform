package org.synanton.common.grpc.validation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rule catalogue used by {@link PgvValidatingServerInterceptor} and REST callers that share
 * the same field contracts (for example {@code TopologyMutation.Grant}).
 */
public class PgvRuleCatalogue {

    public static final Pattern TENANT_ID = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    public static final Pattern SUBJECT_ID = Pattern.compile("^[a-zA-Z0-9_@:.-]{1,128}$");
    public static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Set<String> SUBJECT_TYPES = Set.of("USER", "GROUP");
    private static final Set<String> RESOURCE_TYPES = Set.of("SPACE", "PROJECT", "FOLDER", "DOCUMENT");
    private static final Set<String> PERMISSIONS = Set.of("READ", "WRITE", "ADMIN");

    public List<PgvFieldViolation> validate(String service, String method, Object message) {
        if (message == null) {
            return List.of(new PgvFieldViolation("message", "required", "message must not be null"));
        }
        if ("TopologyMutation".equals(service) && "Grant".equals(method)) {
            return validateGrant(asMap(message));
        }
        if (message instanceof Map<?, ?> map) {
            return validateGrant(stringMap(map));
        }
        return List.of();
    }

    public List<PgvFieldViolation> validateGrant(Map<String, String> fields) {
        List<PgvFieldViolation> violations = new ArrayList<>();
        requirePattern(fields, "tenant_id", TENANT_ID, "string.pattern", violations);
        requirePattern(fields, "subject_id", SUBJECT_ID, "string.pattern", violations);
        requireIn(fields, "subject_type", SUBJECT_TYPES, violations);
        requirePattern(fields, "resource_id", UUID, "string.uuid", violations);
        requireIn(fields, "resource_type", RESOURCE_TYPES, violations);
        requireIn(fields, "permission", PERMISSIONS, violations);
        String idempotency = fields.get("idempotency_key");
        if (idempotency == null || idempotency.isBlank() || idempotency.length() > 256) {
            violations.add(new PgvFieldViolation(
                    "idempotency_key", "string.len", "idempotency_key must be 1-256 characters"));
        }
        return violations;
    }

    private static void requirePattern(
            Map<String, String> fields,
            String field,
            Pattern pattern,
            String error,
            List<PgvFieldViolation> violations
    ) {
        String value = fields.get(field);
        if (value == null || !pattern.matcher(value).matches()) {
            violations.add(new PgvFieldViolation(field, error, field + " failed " + error));
        }
    }

    private static void requireIn(
            Map<String, String> fields,
            String field,
            Set<String> allowed,
            List<PgvFieldViolation> violations
    ) {
        String value = fields.get(field);
        if (value == null || !allowed.contains(value)) {
            violations.add(new PgvFieldViolation(field, "string.in", field + " must be one of " + allowed));
        }
    }

    private static Map<String, String> asMap(Object message) {
        if (message instanceof Map<?, ?> map) {
            return stringMap(map);
        }
        try {
            Method method = message.getClass().getMethod("asValidationMap");
            Object result = method.invoke(message);
            if (result instanceof Map<?, ?> map) {
                return stringMap(map);
            }
        } catch (ReflectiveOperationException ignored) {
            // fall through to bean getters
        }
        return extractGetters(message);
    }

    private static Map<String, String> stringMap(Map<?, ?> map) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null && value != null) {
                out.put(String.valueOf(key), String.valueOf(value));
            }
        });
        return out;
    }

    private static Map<String, String> extractGetters(Object message) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (Method method : message.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || method.getDeclaringClass() == Object.class) {
                continue;
            }
            String name = method.getName();
            if (name.startsWith("get") && name.length() > 3) {
                String field = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                putSnake(out, field, invoke(method, message));
            } else if (method.getParameterCount() == 0 && method.getReturnType() != void.class) {
                putSnake(out, name, invoke(method, message));
            }
        }
        return out;
    }

    private static void putSnake(Map<String, String> out, String field, String value) {
        if (value == null) {
            return;
        }
        StringBuilder snake = new StringBuilder();
        for (int index = 0; index < field.length(); index++) {
            char ch = field.charAt(index);
            if (Character.isUpperCase(ch) && index > 0) {
                snake.append('_');
            }
            snake.append(Character.toLowerCase(ch));
        }
        out.putIfAbsent(snake.toString(), value);
        out.putIfAbsent(field.toLowerCase(Locale.ROOT), value);
    }

    private static String invoke(Method method, Object target) {
        try {
            Object value = method.invoke(target);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
