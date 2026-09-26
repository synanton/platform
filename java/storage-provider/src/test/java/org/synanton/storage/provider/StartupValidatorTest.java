package org.synanton.storage.provider;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.contract.ConformanceEntry;
import org.synanton.storage.contract.ConformanceMatrix;
import org.synanton.storage.contract.ConformanceStatus;
import org.synanton.storage.contract.ProviderIncompatibleException;
import org.synanton.synquest.inmemory.InMemorySynquestEngine;
import org.synanton.synvault.inmemory.InMemorySynvaultStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * YDB-POC-039 acceptance: valid combinations succeed, invalid fail, and the
 * 008 Cassandra-requires-revision case fails fast with a specific error.
 */
class StartupValidatorTest {

    private record StubConformant(String name, ConformanceMatrix matrix) implements Conformant {
        @Override
        public String adapterName() {
            return name;
        }

        @Override
        public String adapterVersion() {
            return "test-1";
        }

        @Override
        public ConformanceMatrix conformance() {
            return matrix;
        }
    }

    private static Conformant full(String name) {
        return new StubConformant(
                name,
                new ConformanceMatrix(
                        name,
                        "test-1",
                        List.of(
                                new ConformanceEntry(Capabilities.SYNVAULT_REVISION, ConformanceStatus.SUPPORTED, "T"),
                                new ConformanceEntry(Capabilities.SYNVAULT_DELETE, ConformanceStatus.SUPPORTED, "T"),
                                new ConformanceEntry(Capabilities.SYNQUEST_LEXICAL, ConformanceStatus.SUPPORTED, "T"))));
    }

    /** Mirrors the real Cassandra matrix shape (008): revision/delete UNSUPPORTED. */
    private static Conformant cassandraLike() {
        return new StubConformant(
                "cassandra",
                new ConformanceMatrix(
                        "cassandra",
                        "test-1",
                        List.of(
                                new ConformanceEntry(Capabilities.SYNVAULT_REVISION, ConformanceStatus.UNSUPPORTED, "008"),
                                new ConformanceEntry(Capabilities.SYNVAULT_DELETE, ConformanceStatus.UNSUPPORTED, "008"),
                                new ConformanceEntry(Capabilities.SYNVAULT_DOCUMENT, ConformanceStatus.SUPPORTED, "T"))));
    }

    private static ProviderRegistry registry(Conformant synvault, Conformant rest) {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(ProviderRegistry.PORT_SYNVAULT, synvault.adapterName(), synvault);
        registry.register(ProviderRegistry.PORT_SYNQUEST, rest.adapterName(), rest);
        registry.register(ProviderRegistry.PORT_WRITER, rest.adapterName(), rest);
        registry.register(ProviderRegistry.PORT_ADMIN, rest.adapterName(), rest);
        return registry;
    }

    @Test
    void validCombinationSucceeds() {
        ProviderRegistry registry = registry(full("inmemory"), full("inmemory"));
        var validated =
                registry.validate(
                        ProviderSelection.uniform("inmemory"), DeploymentRequirements.fullRevision(false));
        assertThat(validated.describe()).contains("synvault=inmemory@test-1");
    }

    @Test
    void mixedProviderCombinationSucceeds() {
        // Outcome 3: persistence and retrieval may live on different backends.
        // Mixing is decided policy (see ProviderSelection), not incidental.
        ProviderRegistry registry = registry(cassandraLike(), full("inmemory"));
        var validated =
                registry.validate(
                        new ProviderSelection("cassandra", "inmemory", "inmemory", "inmemory"),
                        DeploymentRequirements.metadataOnly(false));
        assertThat(validated.activeProviders().byPort())
                .containsEntry("synvault", "cassandra@test-1")
                .containsEntry("synquest", "inmemory@test-1");
    }

    @Test
    void unknownProviderFailsWithAvailableList() {
        ProviderRegistry registry = registry(full("inmemory"), full("inmemory"));
        assertThatThrownBy(
                        () ->
                                registry.validate(
                                        new ProviderSelection("nosuch", "inmemory", "inmemory", "inmemory"),
                                        DeploymentRequirements.metadataOnly(false)))
                .isInstanceOf(ProviderIncompatibleException.class)
                .hasMessageContaining("nosuch")
                .hasMessageContaining("Available: [inmemory]");
    }

    @Test
    void cassandraRequiringRevisionFailsFastWithSpecificError() {
        ProviderRegistry registry = registry(cassandraLike(), full("inmemory"));
        assertThatThrownBy(
                        () ->
                                registry.validate(
                                        new ProviderSelection("cassandra", "inmemory", "inmemory", "inmemory"),
                                        DeploymentRequirements.fullRevision(false)))
                .isInstanceOf(ProviderIncompatibleException.class)
                .hasMessageContaining("cassandra")
                .hasMessageContaining(Capabilities.SYNVAULT_REVISION)
                .hasMessageContaining("revision semantics")
                .satisfies(
                        e ->
                                assertThat(((ProviderIncompatibleException) e).missingCapabilities())
                                        .containsExactly(Capabilities.SYNVAULT_REVISION));
    }

    @Test
    void cassandraMetadataOnlyDeploymentSucceeds() {
        // Same adapter works in a different deployment mode — the error must be
        // requirement-specific, not a blanket rejection.
        ProviderRegistry registry = registry(cassandraLike(), full("inmemory"));
        var validated =
                registry.validate(
                        new ProviderSelection("cassandra", "inmemory", "inmemory", "inmemory"),
                        DeploymentRequirements.metadataOnly(false));
        assertThat(validated.describe()).contains("synvault=cassandra@test-1");
    }

    @Test
    void deleteRequirementFailsFast() {
        ProviderRegistry registry = registry(cassandraLike(), full("inmemory"));
        assertThatThrownBy(
                        () ->
                                registry.validate(
                                        new ProviderSelection("cassandra", "inmemory", "inmemory", "inmemory"),
                                        new DeploymentRequirements(false, true, false)))
                .isInstanceOf(ProviderIncompatibleException.class)
                .hasMessageContaining(Capabilities.SYNVAULT_DELETE);
    }

    @Test
    void unverifiedCapabilitiesBlockedInProduction() {
        Conformant unverified =
                new StubConformant(
                        "exp",
                        new ConformanceMatrix(
                                "exp",
                                "test-1",
                                List.of(
                                        new ConformanceEntry(
                                                Capabilities.SYNVAULT_DOCUMENT, ConformanceStatus.UNVERIFIED, "no test yet"))));
        ProviderRegistry registry = registry(unverified, full("inmemory"));
        assertThatThrownBy(
                        () ->
                                registry.validate(
                                        new ProviderSelection("exp", "inmemory", "inmemory", "inmemory"),
                                        new DeploymentRequirements(false, false, true)))
                .isInstanceOf(ProviderIncompatibleException.class)
                .hasMessageContaining("UNVERIFIED");
        // Same selection is fine outside production.
        registry.validate(
                new ProviderSelection("exp", "inmemory", "inmemory", "inmemory"),
                new DeploymentRequirements(false, false, false));
    }

    @Test
    void realInMemoryMatricesValidate() {
        InMemorySynvaultStore synvault = new InMemorySynvaultStore();
        InMemorySynquestEngine synquest = new InMemorySynquestEngine();
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(ProviderRegistry.PORT_SYNVAULT, "inmemory", synvault);
        registry.register(ProviderRegistry.PORT_SYNQUEST, "inmemory", synquest);
        registry.register(ProviderRegistry.PORT_WRITER, "inmemory", synquest);
        registry.register(ProviderRegistry.PORT_ADMIN, "inmemory", synquest);
        var validated =
                registry.validate(ProviderSelection.uniform("inmemory"), DeploymentRequirements.fullRevision(false));
        assertThat(validated.describe())
                .isEqualTo("admin=inmemory@1.0.0, synquest=inmemory@1.0.0, synvault=inmemory@1.0.0, writer=inmemory@1.0.0");
        assertThat(validated.activeProviders().byPort())
                .containsEntry("synvault", "inmemory@1.0.0");
    }
}
