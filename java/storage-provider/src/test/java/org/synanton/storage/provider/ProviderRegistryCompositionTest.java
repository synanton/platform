package org.synanton.storage.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.Capabilities;
import org.synanton.storage.contract.ConformanceEntry;
import org.synanton.storage.contract.ConformanceMatrix;
import org.synanton.storage.contract.ConformanceStatus;
import org.synanton.storage.contract.Conformant;
import org.synanton.storage.contract.ProviderIncompatibleException;

/**
 * SYN-VECTOR-001 B2.1: metadata.* and vector.* namespaces resolve independently.
 */
class ProviderRegistryCompositionTest {

    private record StubConformant(String name) implements Conformant {
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
            return new ConformanceMatrix(
                    name,
                    "test-1",
                    List.of(
                            new ConformanceEntry(
                                    Capabilities.SYNQUEST_LEXICAL, ConformanceStatus.SUPPORTED, "T")));
        }
    }

    @Test
    void shouldResolveCrossBackendComposition() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(ProviderRegistry.PORT_METADATA, "cassandra", new StubConformant("cassandra"));
        registry.register(ProviderRegistry.PORT_VECTOR, "milvus", new StubConformant("milvus"));

        ProviderRegistry.VectorComposition composition =
                registry.resolveComposition("cassandra", "milvus");

        assertThat(composition.metadata().adapterName()).isEqualTo("cassandra");
        assertThat(composition.vector().adapterName()).isEqualTo("milvus");
    }

    @Test
    void shouldResolveNamespacesIndependently() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(ProviderRegistry.PORT_METADATA, "pg", new StubConformant("pg"));
        registry.register(ProviderRegistry.PORT_VECTOR, "pgvector", new StubConformant("pgvector"));
        registry.register(ProviderRegistry.PORT_VECTOR, "qdrant", new StubConformant("qdrant"));

        assertThat(registry.resolveComposition("pg", "pgvector").vector().adapterName())
                .isEqualTo("pgvector");
        assertThat(registry.resolveComposition("pg", "qdrant").vector().adapterName())
                .isEqualTo("qdrant");
    }

    @Test
    void shouldFailLoudOnUnknownMetadataProvider() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(ProviderRegistry.PORT_VECTOR, "milvus", new StubConformant("milvus"));

        assertThatThrownBy(() -> registry.resolveComposition("nosuchdb", "milvus"))
                .isInstanceOf(ProviderIncompatibleException.class)
                .hasMessageContaining("nosuchdb");
    }

    @Test
    void shouldFailLoudOnUnknownVectorProvider() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(ProviderRegistry.PORT_METADATA, "cassandra", new StubConformant("cassandra"));

        assertThatThrownBy(() -> registry.resolveComposition("cassandra", "nosuchengine"))
                .isInstanceOf(ProviderIncompatibleException.class)
                .hasMessageContaining("nosuchengine");
    }

    @Test
    void shouldLeaveLegacyPortsUntouched() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(ProviderRegistry.PORT_SYNQUEST, "cassandra", new StubConformant("cassandra"));

        assertThat(registry.resolve(ProviderRegistry.PORT_SYNQUEST, "cassandra").adapterName())
                .isEqualTo("cassandra");
        assertThatThrownBy(() -> registry.resolve(ProviderRegistry.PORT_METADATA, "cassandra"))
                .isInstanceOf(ProviderIncompatibleException.class);
    }

    private record RoleConformant(String name, List<String> supported) implements Conformant {
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
            return new ConformanceMatrix(
                    name,
                    "test-1",
                    supported.stream()
                            .map(cap -> new ConformanceEntry(cap, ConformanceStatus.SUPPORTED, "T"))
                            .toList());
        }
    }

    private static ProviderRegistry compositionRegistry() {
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(
                ProviderRegistry.PORT_METADATA,
                "cassandra",
                new RoleConformant(
                        "cassandra",
                        List.of(Capabilities.SYNVAULT_DOCUMENT, Capabilities.SYNQUEST_LEXICAL)));
        registry.register(
                ProviderRegistry.PORT_METADATA,
                "milvus",
                new RoleConformant("milvus", List.of(Capabilities.SYNQUEST_VECTOR)));
        registry.register(
                ProviderRegistry.PORT_VECTOR,
                "milvus",
                new RoleConformant("milvus", List.of(Capabilities.SYNQUEST_VECTOR)));
        registry.register(
                ProviderRegistry.PORT_VECTOR,
                "cassandra",
                new RoleConformant("cassandra", List.of(Capabilities.SYNVAULT_DOCUMENT)));
        registry.register(
                ProviderRegistry.PORT_METADATA,
                "solo",
                new RoleConformant(
                        "solo",
                        List.of(Capabilities.SYNVAULT_DOCUMENT, Capabilities.SYNQUEST_VECTOR)));
        registry.register(
                ProviderRegistry.PORT_VECTOR,
                "solo",
                new RoleConformant(
                        "solo",
                        List.of(Capabilities.SYNVAULT_DOCUMENT, Capabilities.SYNQUEST_VECTOR)));
        return registry;
    }

    @Test
    void shouldValidateCrossBackendComposition() {
        ProviderRegistry.VectorComposition composition =
                compositionRegistry().validateComposition("cassandra", "milvus");

        assertThat(composition.metadata().adapterName()).isEqualTo("cassandra");
        assertThat(composition.vector().adapterName()).isEqualTo("milvus");
    }

    @Test
    void shouldDefaultVectorToMetadataWhenUnset() {
        ProviderRegistry registry = compositionRegistry();

        assertThat(registry.validateComposition("solo", null).vector().adapterName())
                .isEqualTo("solo");
        assertThat(registry.validateComposition("solo", "").vector().adapterName())
                .isEqualTo("solo");
        assertThat(registry.validateComposition("solo", null).metadata().adapterName())
                .isEqualTo("solo");
    }

    @Test
    void shouldRejectVectorRoleWithoutVectorCapability() {
        ProviderRegistry registry = compositionRegistry();

        assertThatThrownBy(() -> registry.validateComposition("cassandra", "cassandra"))
                .isInstanceOf(ProviderIncompatibleException.class)
                .hasMessageContaining("synquest.vector");
    }

    @Test
    void shouldRejectMetadataRoleWithoutDocumentCapability() {
        ProviderRegistry registry = compositionRegistry();

        assertThatThrownBy(() -> registry.validateComposition("milvus", "milvus"))
                .isInstanceOf(ProviderIncompatibleException.class)
                .hasMessageContaining("synvault.document");
    }

    @Test
    void shouldRejectUnknownCompositionMembersLoudly() {
        ProviderRegistry registry = compositionRegistry();

        assertThatThrownBy(() -> registry.validateComposition("nosuchdb", "milvus"))
                .isInstanceOf(ProviderIncompatibleException.class)
                .hasMessageContaining("nosuchdb");
    }
}
