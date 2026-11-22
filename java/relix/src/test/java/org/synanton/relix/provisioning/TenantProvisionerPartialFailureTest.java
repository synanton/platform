package org.synanton.relix.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TenantProvisionerPartialFailureTest {
    private static final byte[] TTL = "ontology".getBytes();

    @Test
    void graphFailureLeavesTenantUnregisteredAndRetryResumes(@TempDir Path audit) throws Exception {
        Fakes f = new Fakes();
        f.graph.failOnce = true;
        TenantProvisioner provisioner = f.provisioner(audit);

        ProvisioningResult failed = provisioner.provision(spec("eval-graph"));

        assertThat(failed.outcome()).isEqualTo("FAILED");
        assertThat(failed.steps()).anySatisfy(step -> {
            assertThat(step.name()).isEqualTo("init_graph");
            assertThat(step.outcome()).isEqualTo("FAILED");
        });
        assertThat(f.store.registered).isFalse();
        assertThat(f.ontology.active).isEqualTo("v1");
        assertThat(f.ontology.createCalls).isEqualTo(1);
        try (var files = Files.list(audit.resolve("failed"))) {
            assertThat(files.count()).isEqualTo(1);
        }

        ProvisioningResult retried = provisioner.provision(spec("eval-graph"));

        assertThat(retried.outcome()).isEqualTo("SUCCESS");
        assertThat(retried.steps()).anySatisfy(step -> {
            assertThat(step.name()).isEqualTo("bind_ontology");
            assertThat(step.outcome()).isEqualTo("ALREADY_PRESENT");
        });
        assertThat(retried.steps()).anySatisfy(step -> {
            assertThat(step.name()).isEqualTo("init_graph");
            assertThat(step.outcome()).isEqualTo("CREATED");
        });
        assertThat(f.store.registered).isTrue();
        assertThat(f.ontology.createCalls).isEqualTo(1);
    }

    @Test
    void registrationFailureLeavesMetadataAndRetryRegisters(@TempDir Path audit) {
        Fakes f = new Fakes();
        f.store.failRegisterOnce = true;
        TenantProvisioner provisioner = f.provisioner(audit);

        ProvisioningResult failed = provisioner.provision(spec("eval-register"));

        assertThat(failed.outcome()).isEqualTo("FAILED");
        assertThat(failed.steps()).anySatisfy(step -> {
            assertThat(step.name()).isEqualTo("register_tenant");
            assertThat(step.outcome()).isEqualTo("FAILED");
        });
        assertThat(f.store.registered).isFalse();
        assertThat(f.ontology.active).isEqualTo("v1");
        assertThat(f.store.provider).isEqualTo("llm");

        ProvisioningResult retried = provisioner.provision(spec("eval-register"));

        assertThat(retried.outcome()).isEqualTo("SUCCESS");
        assertThat(retried.steps()).allSatisfy(step ->
                assertThat(step.outcome()).isIn("ALREADY_PRESENT", "PASSED", "CREATED"));
        assertThat(f.store.registered).isTrue();
    }

    @Test
    void rerunOnProvisionedTenantIsNoOp(@TempDir Path audit) {
        Fakes f = new Fakes();
        TenantProvisioner provisioner = f.provisioner(audit);

        assertThat(provisioner.provision(spec("eval-noop")).outcome()).isEqualTo("SUCCESS");
        Instant registeredAt = f.store.registeredAt;
        int registerCalls = f.store.registerCalls;
        int providerWrites = f.store.providerWrites;

        ProvisioningResult rerun = provisioner.provision(spec("eval-noop"));

        assertThat(rerun.outcome()).isEqualTo("SUCCESS");
        assertThat(rerun.steps()).allSatisfy(step ->
                assertThat(step.outcome()).isIn("ALREADY_PRESENT", "PASSED"));
        assertThat(f.store.registeredAt).isEqualTo(registeredAt);
        assertThat(f.store.registerCalls).isEqualTo(registerCalls);
        assertThat(f.store.providerWrites).isEqualTo(providerWrites);
        assertThat(f.ontology.createCalls).isEqualTo(1);
    }

    @Test
    void emptyStateCheckBlocksNonFreshTenantBeforeRegistration(@TempDir Path audit) {
        Fakes f = new Fakes();
        f.store.sourceVersions = 1;
        TenantProvisioner provisioner = f.provisioner(audit);

        ProvisioningResult result = provisioner.provision(spec("eval-dirty"));

        assertThat(result.outcome()).isEqualTo("FAILED");
        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.name()).isEqualTo("verify_empty_state");
            assertThat(step.outcome()).isEqualTo("BLOCKED");
            assertThat(step.details()).containsEntry("source_versions_count", 1L);
        });
        assertThat(f.store.registered).isFalse();
        assertThat(f.ontology.active).isEqualTo("v1");
        assertThat(f.store.provider).isEqualTo("llm");
    }

    @Test
    void nonEvalTenantIsRefusedBeforeSideEffects(@TempDir Path audit) {
        Fakes f = new Fakes();
        TenantProvisioner provisioner = f.provisioner(audit);

        ProvisioningResult result = provisioner.provision(spec("demo"));

        assertThat(result.outcome()).isEqualTo("FAILED");
        assertThat(f.ontology.createCalls).isZero();
        assertThat(f.graph.calls).isZero();
        assertThat(f.store.registered).isFalse();
    }

    private static TenantProvisioningSpec spec(String tenant) {
        return new TenantProvisioningSpec(tenant, "llm", "v1", TTL, "tester", "abc123");
    }

    private static final class Fakes {
        final Store store = new Store();
        final Ontology ontology = new Ontology();
        final Graph graph = new Graph();

        TenantProvisioner provisioner(Path audit) {
            return new TenantProvisioner(store, ontology, graph,
                    Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC), audit);
        }
    }

    private static final class Store implements ProvisioningStore {
        boolean registered;
        boolean failRegisterOnce;
        String provider;
        long sourceVersions;
        int registerCalls;
        int providerWrites;
        Instant registeredAt;

        @Override public boolean isRegistered(String tenantId) { return registered; }

        @Override public Optional<String> provider(String tenantId) {
            return Optional.ofNullable(provider);
        }

        @Override public void setProvider(String tenantId, String provider, Instant now) {
            this.provider = provider;
            providerWrites++;
        }

        @Override public long sourceVersionCount(String tenantId) { return sourceVersions; }

        @Override public void registerTenant(String tenantId, Instant now) {
            if (failRegisterOnce) {
                failRegisterOnce = false;
                throw new IllegalStateException("registry write failed");
            }
            registered = true;
            registeredAt = now;
            registerCalls++;
        }
    }

    private static final class Ontology implements OntologyProvisioner {
        String active;
        int createCalls;

        @Override public Optional<String> activeVersion(String tenantId) {
            return Optional.ofNullable(active);
        }

        @Override public void createVersion(String tenantId, String version, byte[] ttl) {
            active = version;
            createCalls++;
        }
    }

    private static final class Graph implements GraphProvisioner {
        boolean failOnce;
        boolean created;
        int calls;

        @Override public GraphProvisionOutcome provision(String tenantId) {
            calls++;
            if (failOnce) {
                failOnce = false;
                throw new IllegalStateException("graph init failed");
            }
            if (created) {
                return GraphProvisionOutcome.ALREADY_PRESENT;
            }
            created = true;
            return GraphProvisionOutcome.CREATED;
        }
    }
}
