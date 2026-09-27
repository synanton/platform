package org.synanton.storage.contract;

import java.util.Map;
import java.util.Objects;

/**
 * Point-in-time adapter statistics snapshot (§8.3). Metric and operation names are
 * provider-neutral by construction: operation names are port verbs
 * ({@code synvault.put}, {@code synquest.search}, {@code synquest.upsert}, ...).
 *
 * @param providerId  {@code name@version} (see {@link ActiveProviders})
 * @param operations  per-operation stats, keyed by port verb
 */
public record AdapterStats(String providerId, Map<String, OpStats> operations) {
    public AdapterStats {
        Objects.requireNonNull(providerId, "providerId");
        operations = operations == null ? Map.of() : Map.copyOf(operations);
    }
}
