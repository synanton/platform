package org.synanton.storage.contract;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Typed active-provider snapshot shared by provider selection (039) and the
 * observability contract (038). Both sides consume this type — never a string
 * convention — so 038 dashboards and 039 validation cannot disagree on shape.
 *
 * <p>Values are {@code name@version} per port ({@code synvault}, {@code synquest},
 * {@code writer}, {@code admin}). {@link #describe()} renders the stable,
 * greppable one-line form for logs and health endpoints.
 */
public record ActiveProviders(Map<String, String> byPort) {
    public ActiveProviders {
        Objects.requireNonNull(byPort, "byPort");
        byPort = Map.copyOf(byPort);
        for (Map.Entry<String, String> entry : byPort.entrySet()) {
            if (entry.getKey().isBlank() || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("port and provider id must not be blank");
            }
            if (!entry.getValue().contains("@")) {
                throw new IllegalArgumentException(
                        "provider id must be name@version, got: " + entry.getValue());
            }
        }
    }

    public static ActiveProviders of(String synvault, String synquest, String writer, String admin) {
        return new ActiveProviders(
                Map.of("synvault", synvault, "synquest", synquest, "writer", writer, "admin", admin));
    }

    /** Stable one-line form, keys sorted: {@code admin=.. , synquest=.., synvault=.., writer=..}. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(byPort)
                .forEach((port, id) -> {
                    if (!sb.isEmpty()) {
                        sb.append(", ");
                    }
                    sb.append(port).append('=').append(id);
                });
        return sb.toString();
    }
}
