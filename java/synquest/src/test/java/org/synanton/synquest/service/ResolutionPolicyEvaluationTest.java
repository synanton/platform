package org.synanton.synquest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.synanton.common.resolution.CanonicalEntityLookup;
import org.synanton.common.resolution.ExactTypeCompatibility;
import org.synanton.common.resolution.ResolutionCandidate;
import org.synanton.common.resolution.ResolutionContext;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionOutcome;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.synquest.config.SynquestProperties;
import org.synanton.synquest.engine.lucene.LuceneIndexBuilder;
import org.synanton.synquest.resolution.DefaultResolutionPolicy;
import org.synanton.synquest.resolution.SearchAdapterCandidateProvider;
import org.synanton.synquest.search.EntitySearchDocument;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Evaluation-first 4b.3 harness: real Lucene projection + real Neo4j source of
 * truth + the 10-case fixture. It asserts behavior and prints candidate recall,
 * resolution precision, coverage, ambiguity/unresolved/review rates.
 *
 * <p>This is a contract fixture, not a calibrated benchmark; a larger held-out
 * set is required before broad automatic promotion.
 */
class ResolutionPolicyEvaluationTest {

    private static final String TENANT = "resolution-eval";
    private static final String TENANT_B = "resolution-eval-b";
    private static final String MODEL = "test-model";
    private static final int DIM = 4;

    private static Neo4jContainer<?> neo4j;
    private static Driver driver;

    @TempDir Path indexRoot;

    @BeforeAll
    static void startNeo4j() {
        neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26")).withoutAuthentication();
        neo4j.start();
        driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.none());
        try (Session session = driver.session()) {
            for (EntitySeed entity : entities()) {
                session.run("CREATE (n:Entity {tenant: $tenant, id: $id, label: $label, type: $type})",
                        Map.of("tenant", TENANT, "id", entity.id(),
                                "label", entity.label(), "type", entity.type())).consume();
            }
        }
    }

    @AfterAll
    static void stopNeo4j() {
        if (driver != null) {
            driver.close();
        }
        if (neo4j != null) {
            neo4j.stop();
        }
    }

    @Test
    void tenCaseFixtureMatchesExpectedOutcomesWithHighPrecision() throws Exception {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        CacheCursorStubs.wire(cache);
        when(cache.listManifest(eq(TENANT), anyInt())).thenReturn(List.of());
        when(cache.listManifest(eq(TENANT_B), anyInt())).thenReturn(List.of());
        when(cache.readEmbedding(eq(TENANT), any(), anyInt(), anyString())).thenReturn(Optional.empty());

        SynquestProperties props = new SynquestProperties(
                new SynquestProperties.Index(indexRoot.toString(), false, 30), null,
                new SynquestProperties.Embedding(MODEL, DIM, true, 0), null);
        LuceneIndexBuilder builder = new LuceneIndexBuilder(cache, props);
        QueryEmbedder embedder = mock(QueryEmbedder.class);
        when(embedder.required()).thenReturn(false);
        when(embedder.embedForSearch(anyString(), anyString())).thenAnswer(invocation ->
                new QueryEmbedder.QueryVector(vectorFor(invocation.getArgument(0)), false));
        SearchService service = new SearchService(builder, embedder, props);
        builder.build(TENANT);
        service.initTenant(TENANT);
        builder.build(TENANT_B);
        service.initTenant(TENANT_B);
        service.upsertEntities(TENANT, entities().stream()
                .map(entity -> new EntitySearchDocument(entity.id(), entity.label(), entity.aliases(),
                        entity.type(), entity.description(), entity.embedding()))
                .toList());

        CanonicalEntityLookup lookup = (tenant, entityId) -> {
            try (Session session = driver.session()) {
                var result = session.run(
                        "MATCH (n:Entity {tenant: $tenant, id: $id}) RETURN n.type AS type",
                        Map.of("tenant", tenant, "id", entityId));
                return result.hasNext()
                        ? Optional.of(result.next().get("type").asString())
                        : Optional.empty();
            }
        };
        SearchAdapterCandidateProvider provider = new SearchAdapterCandidateProvider(service, lookup);
        DefaultResolutionPolicy policy = new DefaultResolutionPolicy(
                "v1", 0.02, 0.001, new ExactTypeCompatibility());

        List<FixtureCase> cases = loadFixture();
        int expectedEntityCases = 0;
        int candidateHits = 0;
        int resolved = 0;
        int correctResolved = 0;
        int ambiguous = 0;
        int unresolved = 0;
        int review = 0;

        for (FixtureCase testCase : cases) {
            ResolutionContext base = new ResolutionContext(testCase.tenant(), "fixture-v1", testCase.id(),
                    testCase.mention(), testCase.proposedType(), List.of(), "1.0.0");
            List<ResolutionCandidate> candidates = provider.candidates(base);
            ResolutionContext decisionContext = new ResolutionContext(testCase.tenant(), "fixture-v1",
                    testCase.id(), testCase.mention(), testCase.proposedType(), candidates, "1.0.0");
            ResolutionDecision decision = policy.decide(decisionContext);

            if (testCase.expectedEntityId() != null) {
                expectedEntityCases++;
                if (candidates.stream().anyMatch(candidate ->
                        testCase.expectedEntityId().equals(candidate.entityId()))) {
                    candidateHits++;
                }
            }
            switch (decision.outcome()) {
                case RESOLVED -> {
                    resolved++;
                    if (testCase.expectedEntityId() != null
                            && testCase.expectedEntityId().equals(decision.resolvedEntityId())) {
                        correctResolved++;
                    }
                }
                case AMBIGUOUS -> ambiguous++;
                case UNRESOLVED -> unresolved++;
                case REVIEW_REQUIRED -> review++;
            }
            System.out.printf("case=%-20s expected=%-16s actual=%-16s reason=%-24s candidates=%d top=%s%n",
                    testCase.id(), testCase.expectedOutcome(), decision.outcome(), decision.reasonCode(),
                    candidates.size(), candidates.isEmpty() ? "-" : candidates.getFirst().entityId());
            assertThat(decision.outcome()).as("case " + testCase.id()).isEqualTo(testCase.expectedOutcome());
            if (testCase.expectedOutcome() == ResolutionOutcome.RESOLVED) {
                assertThat(decision.resolvedEntityId()).as("case " + testCase.id())
                        .isEqualTo(testCase.expectedEntityId());
            }
        }

        double candidateRecall = expectedEntityCases == 0 ? 0 : (double) candidateHits / expectedEntityCases;
        double precision = resolved == 0 ? 0 : (double) correctResolved / resolved;
        double coverage = (double) resolved / cases.size();
        System.out.printf("candidate_recall@10=%.3f resolution_precision=%.3f coverage=%.3f "
                        + "ambiguous=%.3f unresolved=%.3f review=%.3f%n",
                candidateRecall, precision, coverage,
                (double) ambiguous / cases.size(), (double) unresolved / cases.size(),
                (double) review / cases.size());

        assertThat(candidateRecall).as("candidate recall").isEqualTo(1.0);
        assertThat(precision).as("resolution precision must be perfect on this fixture").isEqualTo(1.0);
        try (Session session = driver.session()) {
            assertThat(session.run("MATCH ()-[r:RESOLVES_TO]->() RETURN count(r) AS c")
                    .single().get("c").asLong()).as("4b.3 performs no :RESOLVES_TO writes").isZero();
            assertThat(session.run("MATCH ()-[r:REL]->() RETURN count(r) AS c")
                    .single().get("c").asLong()).as("4b.3 performs no canonical promotion").isZero();
        }
    }

    private static List<FixtureCase> loadFixture() throws Exception {
        Path fixture = Path.of("evaluation", "resolution-fixture.jsonl");
        if (!Files.exists(fixture)) {
            fixture = Path.of("..", "..", "evaluation", "resolution-fixture.jsonl");
        }
        ObjectMapper mapper = new ObjectMapper();
        List<FixtureCase> cases = new ArrayList<>();
        for (String line : Files.readAllLines(fixture)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> row = mapper.readValue(line, Map.class);
            cases.add(new FixtureCase(
                    String.valueOf(row.get("id")),
                    String.valueOf(row.get("tenant")),
                    String.valueOf(row.get("mention")),
                    String.valueOf(row.get("proposed_type")),
                    ResolutionOutcome.valueOf(String.valueOf(row.get("expected_outcome"))),
                    row.get("expected_entity_id") == null ? null : String.valueOf(row.get("expected_entity_id"))));
        }
        return cases;
    }

    private record FixtureCase(String id, String tenant, String mention, String proposedType,
                               ResolutionOutcome expectedOutcome, String expectedEntityId) {}

    private record EntitySeed(String id, String label, List<String> aliases, String type,
                              String description, float[] embedding) {}

    private static List<EntitySeed> entities() {
        return List.of(
                new EntitySeed("e-halcyon", "Halcyon-7", List.of("Project Halcyon", "the platform"),
                        "MaintenanceSystem", "auxiliary coolant loop", new float[]{1, 0, 0, 0}),
                new EntitySeed("e-zephyr", "Zephyr-9", List.of("Zephyr", "the platform"),
                        "MaintenanceSystem", "coolant pump", new float[]{0.9f, 0.1f, 0, 0}),
                new EntitySeed("e-zephyr-a", "Zephyr-9A", List.of("Zephyr"),
                        "MaintenanceSystem", "coolant pump variant", new float[]{0.89f, 0.11f, 0, 0}),
                new EntitySeed("e-procure", "Procure-to-Pay", List.of("procurement workflow", "P2P"),
                        "Procedure", "procurement workflow", new float[]{0, 1, 0, 0}),
                new EntitySeed("e-procure-legacy", "P2P Legacy", List.of("P2P"),
                        "Procedure", "legacy purchase flow", new float[]{0, 0.99f, 0.01f, 0}),
                new EntitySeed("e-globex", "Globex Manufacturing", List.of("Globex"),
                        "Organization", "supplier", new float[]{0, 0, 1, 0}),
                new EntitySeed("e-team", "Operations Team", List.of("the operations team", "the team"),
                        "Organization", "operations team", new float[]{0.8f, 0, 0.2f, 0}));
    }

    private static float[] vectorFor(String text) {
        return switch (text) {
            case "Halcyon-7" -> new float[]{1, 0, 0, 0};
            case "Project Halcyon" -> new float[]{1, 0, 0, 0};
            case "the platform" -> new float[]{0.95f, 0.05f, 0, 0};
            case "the procurement workflow" -> new float[]{0, 1, 0, 0};
            case "P2P" -> new float[]{0, 0.995f, 0.005f, 0};
            case "Zephyr" -> new float[]{0.895f, 0.105f, 0, 0};
            case "Globex Manufacturing" -> new float[]{0, 0, 1, 0};
            case "the operations team" -> new float[]{0.8f, 0, 0.2f, 0};
            default -> new float[]{0, 0, 0, 1};
        };
    }
}
