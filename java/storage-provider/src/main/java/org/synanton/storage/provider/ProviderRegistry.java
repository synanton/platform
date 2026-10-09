package org.synanton.storage.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.synanton.storage.contract.ActiveProviders;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.contract.ConformanceMatrix;
import org.synanton.storage.contract.ConformanceStatus;
import org.synanton.storage.contract.ProviderIncompatibleException;

/**
 * Registry of available providers per port. Test and application bootstraps register
 * adapter instances; YDB registers here when 024B lands. Matrices — not docs — are
 * the compatibility source.
 */
public final class ProviderRegistry {

    public static final String PORT_SYNVAULT = "synvault";
    public static final String PORT_SYNQUEST = "synquest";
    public static final String PORT_WRITER = "writer";
    public static final String PORT_ADMIN = "admin";
    /**
     * Composition namespaces (SYN-VECTOR-001 B2.1): the metadata stack and the
     * vector engine resolve independently, so Cassandra+Milvus or PG+Qdrant
     * are expressible without touching the four legacy ports.
     */
    public static final String PORT_METADATA = "metadata";
    public static final String PORT_VECTOR = "vector";

    private final Map<String, Map<String, Conformant>> providers = new LinkedHashMap<>();

    public ProviderRegistry register(String port, String name, Conformant adapter) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(adapter, "adapter");
        providers.computeIfAbsent(port, p -> new LinkedHashMap<>()).put(name, adapter);
        return this;
    }

    public Conformant resolve(String port, String name) {
        Map<String, Conformant> byName = providers.getOrDefault(port, Map.of());
        Conformant adapter = byName.get(name);
        if (adapter == null) {
            throw new ProviderIncompatibleException(
                    name,
                    List.of(port),
                    "unknown provider for port '"
                            + port
                            + "'. Available: "
                            + byName.keySet()
                            + ".");
        }
        return adapter;
    }

    /**
     * Resolves a metadata × vector composition: each namespace resolves
     * independently against its own registrations, so cross-backend pairs
     * need no special casing. Unknown names fail loud via {@link #resolve}.
     */
    public VectorComposition resolveComposition(String metadataName, String vectorName) {
        return new VectorComposition(
                resolve(PORT_METADATA, metadataName), resolve(PORT_VECTOR, vectorName));
    }

    /** A resolved metadata-stack + vector-engine pair. */
    public record VectorComposition(Conformant metadata, Conformant vector) {}

    /**
     * Validates a metadata × vector composition (SYN-VECTOR-001 B2.2).
     * A blank vector name defaults to the metadata name, so single-provider
     * deployments stay zero-config. Role checks are capability-based: the
     * metadata side must store documents, the vector side must serve vectors.
     * Every failure names the provider, the capability, and the reason.
     */
    public VectorComposition validateComposition(String metadataName, String vectorName) {
        String vector = (vectorName == null || vectorName.isBlank()) ? metadataName : vectorName;
        Conformant metadata = resolve(PORT_METADATA, metadataName);
        Conformant vectorAdapter = resolve(PORT_VECTOR, vector);
        require(
                metadata,
                Capabilities.SYNVAULT_DOCUMENT,
                true,
                "metadata role requires document storage but adapter '"
                        + metadata.adapterName() + "' does not report it");
        require(
                vectorAdapter,
                Capabilities.SYNQUEST_VECTOR,
                true,
                "vector role requires vector search but adapter '"
                        + vectorAdapter.adapterName() + "' does not report it");
        return new VectorComposition(metadata, vectorAdapter);
    }

    /**
     * Validates a selection against deployment requirements. Fails fast with a
     * specific error naming the provider, the missing capability, and the reason —
     * including the 008 Cassandra revision case.
     */
    public ValidatedSelection validate(ProviderSelection selection, DeploymentRequirements requirements) {
        Conformant synvault = resolve(PORT_SYNVAULT, selection.synvault());
        Conformant synquest = resolve(PORT_SYNQUEST, selection.synquest());
        Conformant writer = resolve(PORT_WRITER, selection.writer());
        Conformant admin = resolve(PORT_ADMIN, selection.admin());

        require(synvault, Capabilities.SYNVAULT_REVISION, requirements.requireRevisionSemantics(),
                "deployment requires revision semantics (putDocumentRevision atomicity)");
        require(synvault, Capabilities.SYNVAULT_DELETE, requirements.requireDeleteSemantics(),
                "deployment requires delete semantics");
        if (requirements.production()) {
            for (Conformant adapter : List.of(synvault, synquest, writer, admin)) {
                List<String> unverified =
                        adapter.conformance().entries().stream()
                                .filter(e -> e.status() == ConformanceStatus.UNVERIFIED)
                                .map(e -> e.capability())
                                .toList();
                if (!unverified.isEmpty()) {
                    throw new ProviderIncompatibleException(
                            adapter.adapterName(),
                            unverified,
                            "UNVERIFIED capabilities cannot be enabled in production. "
                                    + "Complete conformance evidence first (§9.3).");
                }
            }
        }
        return new ValidatedSelection(selection, synvault, synquest, writer, admin);
    }

    private static void require(Conformant adapter, String capability, boolean required, String why) {
        if (!required) {
            return;
        }
        ConformanceMatrix matrix = adapter.conformance();
        if (matrix.supported(capability)) {
            return;
        }
        String reason =
                matrix.entry(capability)
                        .map(e -> e.status() + " (" + e.evidence() + ")")
                        .orElse("not reported by adapter");
        throw new ProviderIncompatibleException(
                adapter.adapterName(),
                List.of(capability),
                why + " but adapter '" + adapter.adapterName() + "@" + adapter.adapterVersion()
                        + "' reports " + reason + ".");
    }

    /** Validated binding of selection to adapter matrices. */
    public record ValidatedSelection(
            ProviderSelection selection,
            Conformant synvault,
            Conformant synquest,
            Conformant writer,
            Conformant admin) {

        /** Typed snapshot for the observability contract (038 consumes this type). */
        public ActiveProviders activeProviders() {
            return ActiveProviders.of(id(synvault), id(synquest), id(writer), id(admin));
        }

        /** One-line active-provider summary for logs and health endpoints. */
        public String describe() {
            return activeProviders().describe();
        }

        private static String id(Conformant adapter) {
            return adapter.adapterName() + "@" + adapter.adapterVersion();
        }
    }
}
