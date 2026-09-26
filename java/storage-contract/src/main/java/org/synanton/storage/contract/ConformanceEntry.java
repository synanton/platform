package org.synanton.storage.contract;

import java.util.Objects;

/**
 * One row of a {@link ConformanceMatrix}: a capability, its status, and — for
 * {@link ConformanceStatus#SUPPORTED} — the fully-qualified contract-test class that
 * evidences it. Evidence must name a loadable test class (checked by the gating
 * contract suite); anything else is treated as {@code UNVERIFIED}.
 *
 * @param capability dotted capability name, see {@link Capabilities}
 * @param status     conformance status
 * @param evidence   supporting detail: test class name when supported, decision or
 *                   reason reference when unsupported (e.g. "008"), explanation when
 *                   unverified; never blank
 */
public record ConformanceEntry(String capability, ConformanceStatus status, String evidence) {
    public ConformanceEntry {
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(evidence, "evidence");
        if (capability.isBlank() || evidence.isBlank()) {
            throw new IllegalArgumentException("capability and evidence must not be blank");
        }
    }

    public static ConformanceEntry supported(String capability, String evidenceTestClass) {
        return new ConformanceEntry(capability, ConformanceStatus.SUPPORTED, evidenceTestClass);
    }

    public static ConformanceEntry unsupported(String capability, String reason) {
        return new ConformanceEntry(capability, ConformanceStatus.UNSUPPORTED, reason);
    }

    public static ConformanceEntry unverified(String capability, String explanation) {
        return new ConformanceEntry(capability, ConformanceStatus.UNVERIFIED, explanation);
    }
}
