package org.synanton.storage.contract;

/**
 * Implemented by every storage/search adapter. Exposes the machine-readable
 * {@link ConformanceMatrix} that capability gating (020) and startup validation
 * (039) consume.
 */
public interface Conformant {

    /** Adapter identity, e.g. {@code "cassandra"} / {@code "inmemory"} / {@code "ydb"}. */
    String adapterName();

    /** Adapter implementation version. */
    String adapterVersion();

    /** Per-capability conformance with evidence. */
    ConformanceMatrix conformance();
}
