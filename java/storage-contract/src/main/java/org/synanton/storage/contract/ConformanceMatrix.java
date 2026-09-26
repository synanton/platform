package org.synanton.storage.contract;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Machine-readable per-adapter, per-capability conformance matrix (§9.3, YDB-POC-020).
 * This is the artifact startup validation (039) consumes — not docs.
 *
 * <p>Rules: every capability the adapter advertises as a {@code true} flag must have
 * a {@code SUPPORTED} entry with loadable test evidence; anything else is
 * {@code UNSUPPORTED} or {@code UNVERIFIED}. The gating contract suite enforces this.
 */
public record ConformanceMatrix(String adapterName, String adapterVersion, List<ConformanceEntry> entries) {
    public ConformanceMatrix {
        Objects.requireNonNull(adapterName, "adapterName");
        Objects.requireNonNull(adapterVersion, "adapterVersion");
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (adapterName.isBlank()) {
            throw new IllegalArgumentException("adapterName must not be blank");
        }
    }

    public Optional<ConformanceEntry> entry(String capability) {
        return entries.stream().filter(e -> e.capability().equals(capability)).findFirst();
    }

    public boolean supported(String capability) {
        return entry(capability).map(e -> e.status() == ConformanceStatus.SUPPORTED).orElse(false);
    }

    /** Capabilities in a failing state for production enablement. */
    public List<ConformanceEntry> blocking() {
        return entries.stream()
                .filter(e -> e.status() != ConformanceStatus.SUPPORTED)
                .toList();
    }
}
