package org.synanton.storage.provider;

import java.util.Objects;

/**
 * Configuration-driven provider selection (proposal §8.2, YDB-POC-039). Names one
 * adapter per port; a single deployment switches providers without code change.
 * Resolution and compatibility checks happen in {@link StartupValidator}.
 *
 * <p><strong>Mixed-provider configurations are explicitly supported</strong>
 * (Outcome 3: persistence on one backend, retrieval on another). Each port
 * validates independently against its own matrix; no uniformity rule is enforced.
 *
 * @param synvault provider name for {@code SynvaultStore} (e.g. cassandra, ydb, inmemory)
 * @param synquest provider name for {@code SynquestEngine}
 * @param writer   provider name for {@code SynquestIndexWriter}
 * @param admin    provider name for {@code SynquestIndexAdmin}
 */
public record ProviderSelection(String synvault, String synquest, String writer, String admin) {
    public ProviderSelection {
        Objects.requireNonNull(synvault, "synvault");
        Objects.requireNonNull(synquest, "synquest");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(admin, "admin");
    }

    /** Single-provider deployment (all four ports on one backend). */
    public static ProviderSelection uniform(String provider) {
        return new ProviderSelection(provider, provider, provider, provider);
    }
}
