package org.synanton.relix.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ordered, idempotent provisioning. Registration is last so consumers never see
 * a tenant before its ontology, graph, provider, and empty-state checks pass.
 */
public final class TenantProvisioner {
    private static final String CLI_VERSION = "1.0.0";
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC);

    private final ProvisioningStore store;
    private final OntologyProvisioner ontology;
    private final GraphProvisioner graph;
    private final Clock clock;
    private final Path auditDir;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public TenantProvisioner(ProvisioningStore store, OntologyProvisioner ontology,
                             GraphProvisioner graph, Clock clock, Path auditDir) {
        this.store = store;
        this.ontology = ontology;
        this.graph = graph;
        this.clock = clock;
        this.auditDir = auditDir;
    }

    public ProvisioningResult provision(TenantProvisioningSpec spec) {
        Instant started = clock.instant();
        List<ProvisioningStep> steps = new ArrayList<>();
        String failure = null;
        String requestedProvider = spec.provider() == null ? "" : spec.provider().strip();
        String provider = canonicalProvider(requestedProvider);

        try {
            long t0 = System.nanoTime();
            if (spec.tenantId() == null || !spec.tenantId().startsWith("eval-")) {
                throw new ProvisioningFailure("tenant id must start with eval-");
            }
            if (provider == null) {
                throw new ProvisioningFailure(
                        "unsupported provider '" + requestedProvider + "' (want regex|llm|vllm)");
            }
            if (spec.ontologyVersion() == null || spec.ontologyVersion().isBlank()) {
                throw new ProvisioningFailure("ontology version is required");
            }
            steps.add(new ProvisioningStep("validate_request", "PASSED", ms(t0), Map.of()));

            t0 = System.nanoTime();
            Optional<String> active = ontology.activeVersion(spec.tenantId());
            if (active.isPresent()) {
                if (!active.get().equals(spec.ontologyVersion())) {
                    throw new ProvisioningFailure("tenant already bound to ontology "
                            + active.get() + ", expected " + spec.ontologyVersion());
                }
                steps.add(new ProvisioningStep("bind_ontology", "ALREADY_PRESENT", ms(t0),
                        Map.of("ontology_version", active.get())));
            } else {
                ontology.createVersion(spec.tenantId(), spec.ontologyVersion(),
                        spec.ontologyTtl() == null ? new byte[0] : spec.ontologyTtl());
                steps.add(new ProvisioningStep("bind_ontology", "CREATED", ms(t0),
                        Map.of("ontology_version", spec.ontologyVersion())));
            }

            t0 = System.nanoTime();
            GraphProvisionOutcome graphOutcome;
            try {
                graphOutcome = graph.provision(spec.tenantId());
            } catch (Exception e) {
                steps.add(new ProvisioningStep("init_graph", "FAILED", ms(t0),
                        Map.of("error", String.valueOf(e.getMessage()))));
                throw new ProvisioningFailure("graph metadata provisioning failed: " + e.getMessage());
            }
            if (graphOutcome == GraphProvisionOutcome.FAILED) {
                steps.add(new ProvisioningStep("init_graph", "FAILED", ms(t0), Map.of()));
                throw new ProvisioningFailure("graph metadata provisioning failed");
            }
            steps.add(new ProvisioningStep("init_graph",
                    graphOutcome == GraphProvisionOutcome.CREATED ? "CREATED" : "ALREADY_PRESENT",
                    ms(t0), Map.of()));

            t0 = System.nanoTime();
            Optional<String> existing = store.provider(spec.tenantId());
            if (existing.isPresent()) {
                if (!provider.equals(canonicalProvider(existing.get()))) {
                    throw new ProvisioningFailure("tenant already has provider "
                            + existing.get() + ", expected " + provider);
                }
                steps.add(new ProvisioningStep("set_provider", "ALREADY_PRESENT", ms(t0),
                        Map.of("provider", provider)));
            } else {
                store.setProvider(spec.tenantId(), provider, clock.instant());
                steps.add(new ProvisioningStep("set_provider", "CREATED", ms(t0),
                        Map.of("provider", provider)));
            }

            t0 = System.nanoTime();
            long sourceVersions = store.sourceVersionCount(spec.tenantId());
            if (sourceVersions > 0) {
                steps.add(new ProvisioningStep("verify_empty_state", "BLOCKED", ms(t0),
                        Map.of("source_versions_count", sourceVersions)));
                throw new ProvisioningFailure("tenant is not fresh: " + sourceVersions
                        + " source version(s) already exist");
            }
            steps.add(new ProvisioningStep("verify_empty_state", "PASSED", ms(t0),
                    Map.of("source_versions_count", 0)));

            t0 = System.nanoTime();
            if (store.isRegistered(spec.tenantId())) {
                steps.add(new ProvisioningStep("register_tenant", "ALREADY_PRESENT", ms(t0),
                        Map.of()));
            } else {
                try {
                    store.registerTenant(spec.tenantId(), clock.instant());
                } catch (Exception e) {
                    steps.add(new ProvisioningStep("register_tenant", "FAILED", ms(t0),
                            Map.of("error", String.valueOf(e.getMessage()))));
                    throw new ProvisioningFailure("tenant registration failed: " + e.getMessage());
                }
                steps.add(new ProvisioningStep("register_tenant", "CREATED", ms(t0), Map.of()));
            }
        } catch (ProvisioningFailure e) {
            failure = e.getMessage();
        } catch (Exception e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        ProvisioningResult result = new ProvisioningResult(
                spec.tenantId(), requestedProvider, CLI_VERSION, spec.gitSha(), spec.operator(),
                started.toString(), clock.instant().toString(),
                failure == null ? "SUCCESS" : "FAILED", List.copyOf(steps), failure);
        writeAudit(result);
        return result;
    }

    private void writeAudit(ProvisioningResult result) {
        try {
            Path target = auditDir;
            if (!"SUCCESS".equals(result.outcome())) {
                target = auditDir.resolve("failed");
            }
            Files.createDirectories(target);
            String filename = result.tenantId() + "-" + FILE_TIME.format(clock.instant())
                    + "-" + UUID.randomUUID().toString().substring(0, 8) + ".json";
            mapper.writeValue(target.resolve(filename).toFile(), result);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write provisioning audit", e);
        }
    }

    private static String canonicalProvider(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw) {
            case "regex" -> "regex";
            case "llm", "vllm" -> "llm";
            default -> null;
        };
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static final class ProvisioningFailure extends RuntimeException {
        private ProvisioningFailure(String message) {
            super(message);
        }
    }
}
