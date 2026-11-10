package org.synanton.relix.integration;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.synanton.common.resolution.ExactTypeCompatibility;
import org.synanton.common.resolution.ResolutionCandidate;
import org.synanton.common.resolution.ResolutionCandidateProvider;
import org.synanton.common.resolution.ResolutionDecision;
import org.synanton.common.resolution.ResolutionOutcome;
import org.synanton.common.resolution.ResolutionPolicy;
import org.synanton.common.resolution.RetryableProcessingException;
import org.synanton.common.resolution.StaleResolutionException;
import org.synanton.ingestioncache.claim.ClaimService;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.RelationPromotionResultRow;
import org.synanton.ingestioncache.domain.ResolutionWorkRow;
import org.synanton.relix.adapter.out.graph.neo4j.CypherExecutor;
import org.synanton.relix.adapter.out.graph.neo4j.Neo4jGraphConnector;
import org.synanton.relix.config.PromotionConsumerProperties;
import org.synanton.relix.config.ReconciliationProperties;
import org.synanton.relix.consumer.PromotionConsumer;
import org.synanton.relix.graph.GraphLoader;
import org.synanton.relix.graph.RelixCanonicalRelationPromoter;
import org.synanton.relix.reconciliation.ResolutionReconciler;
import org.synanton.synquest.config.ResolutionConsumerProperties;
import org.synanton.synquest.resolution.ResolutionConsumer;
import org.synanton.synquest.resolution.ResolutionWriteClient;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** #589: real Cassandra + Neo4j fault injection for consumers/reconciliation. No vLLM/TEI. */
class ConsumerFaultInjectionIntegrationTest {

    private static final String TENANT = "tenant-589";
    private static final String CONNECTOR = "connector-589";
    private static final String SOURCE = "source-589";
    private static final String RESOURCE = "resource-589";
    private static final UUID VERSION = UUID.fromString("11111111-5890-5890-5890-111111111111");
    private static final UUID CONTENT_REF = UUID.fromString("22222222-5890-5890-5890-222222222222");

    private static CassandraContainer cassandra;
    private static Neo4jContainer<?> neo4j;
    private static CqlSession session;
    private static IngestionCacheClient cache;
    private static ClaimService claims;
    private static Driver driver;
    private static CypherExecutor cypher;
    private static Neo4jGraphConnector graph;

    @BeforeAll
    static void start() {
        cassandra = new CassandraContainer(DockerImageName.parse("cassandra:4.1"));
        cassandra.start();
        InetSocketAddress contact = new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042));
        try (CqlSession admin = CqlSession.builder().addContactPoint(contact)
                .withLocalDatacenter("datacenter1").build()) {
            admin.execute("CREATE KEYSPACE IF NOT EXISTS ingestion_cache WITH replication = "
                    + "{'class':'SimpleStrategy','replication_factor':1}");
        }
        session = CqlSession.builder().addContactPoint(contact)
                .withLocalDatacenter("datacenter1").withKeyspace("ingestion_cache").build();
        SchemaInstaller.install(session);
        cache = new IngestionCacheClient(session);
        claims = new ClaimService(cache);
        seedSource();

        neo4j = new Neo4jContainer<>(DockerImageName.parse("neo4j:5.26")).withoutAuthentication();
        neo4j.start();
        driver = GraphDatabase.driver(neo4j.getBoltUrl(), AuthTokens.none());
        cypher = new SessionCypher(driver);
        graph = new Neo4jGraphConnector(mock(GraphLoader.class), cypher);
    }

    @AfterAll
    static void stop() {
        if (driver != null) {
            driver.close();
        }
        if (session != null) {
            session.close();
        }
        if (neo4j != null) {
            neo4j.stop();
        }
        if (cassandra != null) {
            cassandra.stop();
        }
    }

    @Test
    void lostResponseAfterCommitRetriesWithoutDuplicatingTheEdge() throws Exception {
        String mention = mention("lost");
        String entity = entity("lost");
        createResolvedMention(mention, "Acme", "Organization", entity);
        insertWork(mention, "Acme", "Organization");

        TestWriter writer = new TestWriter(Mode.COMMIT_THEN_TIMEOUT);
        ResolutionConsumer consumer = consumer(writer, Map.of("Acme", entity), 1);
        consumer.poll();

        ResolutionWorkRow afterFailure = work(mention);
        assertThat(afterFailure.claimStatus()).isEqualTo("PENDING");
        assertThat(afterFailure.attemptCount()).isEqualTo(1);
        assertThat(afterFailure.outcome()).isNull();
        assertThat(activeEdgeCount(mention)).isEqualTo(1);

        Thread.sleep(1_200);
        consumer.poll();

        ResolutionWorkRow completed = work(mention);
        assertThat(completed.claimStatus()).isEqualTo("COMPLETED");
        assertThat(completed.attemptCount()).isEqualTo(2);
        assertThat(completed.outcome()).isEqualTo("RESOLVED");
        assertThat(activeEdgeCount(mention)).isEqualTo(1);
        assertThat(activeEntity(mention)).isEqualTo(entity);
        assertThat(writer.calls).isEqualTo(2);
    }

    @Test
    void transientFailureStaysPendingAndReconciliationDoesNotCompleteIt() {
        String mention = mention("transient");
        String entity = entity("transient");
        createResolvedMention(mention, "Acme", "Organization", entity);
        insertWork(mention, "Acme", "Organization");

        ResolutionConsumer consumer = consumer(
                new TestWriter(Mode.THROW_TIMEOUT), Map.of("Acme", entity), 30);
        consumer.poll();

        ResolutionWorkRow pending = work(mention);
        assertThat(pending.claimStatus()).isEqualTo("PENDING");
        assertThat(pending.attemptCount()).isEqualTo(1);
        assertThat(pending.outcome()).isNull();
        assertThat(activeEdgeCount(mention)).isZero();

        ResolutionReconciler reconciler = new ResolutionReconciler(cache, claims, graph,
                new ReconciliationProperties(true, TENANT, 100, 60000L, 60),
                new SimpleMeterRegistry());
        reconciler.poll();

        assertThat(work(mention).claimStatus()).isEqualTo("PENDING");
        assertThat(work(mention).outcome()).isNull();
        assertThat(activeEdgeCount(mention)).isZero();
    }

    @Test
    void missingTargetIsTerminalStaleNotAnInfiniteRetry() {
        String mention = mention("missing");
        createResolvedMention(mention, "Acme", "Organization", entity("missing"));
        insertWork(mention, "Acme", "Organization");

        ResolutionConsumer consumer = consumer(new TestWriter(Mode.NORMAL),
                Map.of("Acme", "entity-that-does-not-exist"), 1);
        consumer.poll();

        ResolutionWorkRow row = work(mention);
        assertThat(row.claimStatus()).isEqualTo("SUPERSEDED");
        assertThat(row.attemptCount()).isEqualTo(1);
        assertThat(activeEdgeCount(mention)).isZero();
    }

    @Test
    void expiredWorkerCannotOverwriteAReclaimedWorkersDecision() {
        String mention = mention("reclaim");
        String entityA = entity("reclaim-a");
        String entityB = entity("reclaim-b");
        createResolvedMention(mention, "Acme", "Organization", entityA);
        createEntity(entityB, "Acme", "Organization");
        insertWork(mention, "Acme", "Organization");

        Instant now = Instant.now();
        ResolutionWorkRow work = work(mention);
        assertThat(cache.claimResolutionWork(work, "worker-A", now, Duration.ofSeconds(-1))).isTrue();
        ResolutionWorkRow claimedA = work(mention);

        assertThat(cache.reclaimResolutionWork(claimedA, now)).isTrue();
        assertThat(cache.claimResolutionWork(claimedA, "worker-B", now, Duration.ofMinutes(1))).isTrue();
        ResolutionWorkRow claimedB = work(mention);
        assertThat(claimedB.attemptCount()).isEqualTo(2);

        ResolutionDecision decisionB = decision(mention, entityB, 2);
        graph.upsertResolution(decisionB);
        assertThat(cache.completeResolutionWork(claimedB, "worker-B", "RESOLVED", "TEST", entityB,
                "Acme", 0.9, 0.0, "v1", "active", "{}", Instant.now())).isTrue();

        ResolutionDecision staleA = decision(mention, entityA, 1);
        assertThatThrownBy(() -> graph.upsertResolution(staleA))
                .isInstanceOf(StaleResolutionException.class);
        assertThat(cache.supersedeResolutionWork(claimedA, "worker-A", "stale fence", now)).isFalse();
        assertThat(activeEntity(mention)).isEqualTo(entityB);
        assertThat(activeEdgeCount(mention)).isEqualTo(1);
    }

    @Test
    void restartRecoversGraphCommittedWorkWithoutDuplicatingState() {
        String mention = mention("restart");
        String entity = entity("restart");
        createResolvedMention(mention, "Acme", "Organization", entity);
        insertWork(mention, "Acme", "Organization");

        TestWriter crashing = new TestWriter(Mode.COMMIT_THEN_ERROR);
        ResolutionConsumer first = consumer(crashing, Map.of("Acme", entity), 1);
        assertThatThrownBy(first::poll).isInstanceOf(AssertionError.class);

        ResolutionWorkRow stranded = work(mention);
        assertThat(stranded.claimStatus()).isEqualTo("PROCESSING");
        assertThat(stranded.attemptCount()).isEqualTo(1);
        assertThat(activeEdgeCount(mention)).isEqualTo(1);

        session.execute(SimpleStatement.newInstance(
                "UPDATE ingestion_cache.resolution_results SET lease_expires_at=? WHERE tenant_id=? "
                        + "AND source_version_id=? AND mention_id=?",
                Instant.now().minusSeconds(120), TENANT, VERSION, mention));

        TestWriter recoveredWriter = new TestWriter(Mode.NORMAL);
        ResolutionConsumer restarted = consumer(recoveredWriter, Map.of("Acme", entity), 1);
        restarted.poll();

        ResolutionWorkRow completed = work(mention);
        assertThat(completed.claimStatus()).isEqualTo("COMPLETED");
        assertThat(completed.attemptCount()).isEqualTo(2);
        assertThat(completed.outcome()).isEqualTo("RESOLVED");
        assertThat(activeEdgeCount(mention)).isEqualTo(1);
        assertThat(recoveredWriter.calls).isEqualTo(1);
    }

    @Test
    void fullResolutionThenPromotionCreatesOneRelAndEvidence() {
        String subject = mention("subject");
        String object = mention("object");
        String subjectEntity = entity("subject");
        String objectEntity = entity("object");
        createResolvedMention(subject, "Acme", "Organization", subjectEntity);
        createResolvedMention(object, "Protocol-A", "Protocol", objectEntity);
        insertWork(subject, "Acme", "Organization");
        insertWork(object, "Protocol-A", "Protocol");

        TestWriter writer = new TestWriter(Mode.NORMAL);
        ResolutionConsumer resolution = consumer(writer,
                Map.of("Acme", subjectEntity, "Protocol-A", objectEntity), 1);
        resolution.poll();

        assertThat(work(subject).claimStatus()).isEqualTo("COMPLETED");
        assertThat(work(object).claimStatus()).isEqualTo("COMPLETED");
        assertThat(activeEdgeCount(subject)).isEqualTo(1);
        assertThat(activeEdgeCount(object)).isEqualTo(1);

        String relationId = "relation-589";
        String evidenceJson = """
                {"view":{"relationId":"%s","tenantId":"%s","sourceVersionId":"%s",
                "subjectMentionId":"%s","objectMentionId":"%s","predicate":"requires",
                "evidenceChunkId":"%s:0","evidenceChunkOffsetStart":0,
                "evidenceChunkOffsetEnd":%d}}
                """.formatted(relationId, TENANT, VERSION, subject, object, CONTENT_REF,
                "Acme requires Protocol-A".length());
        cache.upsertRelationPromotionResult(new RelationPromotionResultRow(
                TENANT, VERSION, relationId, subject, object, "requires", "VALID",
                subjectEntity, objectEntity, "VALID", evidenceJson, "relation-validation-v1",
                "active", Instant.now(), null));

        RelixCanonicalRelationPromoter promoter = new RelixCanonicalRelationPromoter(cache, graph);
        PromotionConsumer promotion = new PromotionConsumer(claims, promoter,
                new PromotionConsumerProperties(true, TENANT, 5, 5, 1, 60, 1000L),
                new SimpleMeterRegistry());
        promotion.poll();

        assertThat(count("MATCH ()-[r:REL {tenant_id: $tenant}]->() RETURN count(r) AS c")).isEqualTo(1);
        assertThat(count("MATCH (e:RelationEvidence {tenant_id: $tenant}) RETURN count(e) AS c")).isEqualTo(1);
        assertThat(count("MATCH (r:ExtractedRelation {tenant_id: $tenant}) RETURN count(r) AS c")).isEqualTo(1);
        assertThat(count("MATCH ()-[s:SUPPORTS {tenant_id: $tenant}]->() RETURN count(s) AS c")).isEqualTo(1);
        assertThat(cache.listRelationPromotionWorkRows(TENANT, VERSION)).singleElement()
                .satisfies(row -> assertThat(row.claimStatus()).isEqualTo("COMPLETED"));
    }

    private static ResolutionConsumer consumer(ResolutionWriteClient writer,
                                               Map<String, String> entityBySurface, int backoffSeconds) {
        ResolutionCandidateProvider provider = context -> {
            String entity = entityBySurface.get(context.surfaceForm());
            if (entity == null) {
                return List.of();
            }
            return List.of(new ResolutionCandidate(entity, context.surfaceForm(),
                    context.proposedType(), 0.9, "label", "RRF"));
        };
        ResolutionPolicy policy = new org.synanton.synquest.resolution.DefaultResolutionPolicy(
                "v1", 0.02, 0.001, new ExactTypeCompatibility());
        ResolutionConsumerProperties properties = new ResolutionConsumerProperties(
                true, TENANT, 10, 5, backoffSeconds, 60, 1000L, "active");
        return new ResolutionConsumer(claims, provider, policy, writer, properties, new SimpleMeterRegistry());
    }

    private static void seedSource() {
        Instant now = Instant.now();
        session.execute(SimpleStatement.newInstance(
                "INSERT INTO ingestion_cache.sources (tenant_id, source_id, connector_id, connector_type, "
                        + "status, created_at, capability_flags) VALUES (?,?,?,?,?,?,?)",
                TENANT, SOURCE, CONNECTOR, "test", "ACTIVE", now, Set.of()));
        session.execute(SimpleStatement.newInstance(
                "INSERT INTO ingestion_cache.source_identities (tenant_id, source_id, connector_id, resource_id) "
                        + "VALUES (?,?,?,?)", TENANT, SOURCE, CONNECTOR, RESOURCE));
        session.execute(SimpleStatement.newInstance(
                "INSERT INTO ingestion_cache.source_versions (tenant_id, connector_id, source_id, resource_id, "
                        + "version_id, content_ref_id, observed_at) VALUES (?,?,?,?,?,?,?)",
                TENANT, CONNECTOR, SOURCE, RESOURCE, VERSION, CONTENT_REF, now));
    }

    private static void createResolvedMention(String mention, String surface, String type, String entity) {
        String chunkId = CONTENT_REF + ":0";
        graph.upsertMentions(List.of(new org.synanton.relix.graph.ExtractedMention(
                mention, TENANT, VERSION.toString(), CONTENT_REF.toString(), chunkId,
                0, surface.length(), 0, surface.length(), surface, type, Instant.now())));
        createEntity(entity, surface, type);
    }

    private static void createEntity(String entity, String label, String type) {
        cypher.write("MERGE (e:Entity {tenant: $tenant, id: $id}) SET e.label = $label, e.type = $type",
                Map.of("tenant", TENANT, "id", entity, "label", label, "type", type));
    }

    private static void insertWork(String mention, String surface, String type) {
        cache.insertResolutionWork(new ResolutionWorkRow(
                TENANT, VERSION, mention, CONNECTOR, SOURCE, RESOURCE, CONTENT_REF, 0,
                0, surface.length(), 0, surface.length(), surface, type,
                "PENDING", null, null, 0, null, null, Instant.now(), null, null, null));
    }

    private static ResolutionWorkRow work(String mention) {
        return cache.listResolutionWorkRows(TENANT, VERSION).stream()
                .filter(row -> row.mentionId().equals(mention)).findFirst().orElseThrow();
    }

    private static ResolutionDecision decision(String mention, String entity, long fence) {
        return new ResolutionDecision(TENANT, VERSION.toString(), mention, ResolutionOutcome.RESOLVED,
                "TEST", entity, "Acme", 0.9, 0.0, "v1", "active", Map.of(), "RRF", fence);
    }

    private static long activeEdgeCount(String mention) {
        return count("MATCH (m:ExtractedMention {tenant_id: $tenant, mention_id: $mention}) "
                + "-[r:RESOLVES_TO {active: true}]->() RETURN count(r) AS c",
                Map.of("tenant", TENANT, "mention", mention));
    }

    private static String activeEntity(String mention) {
        List<Map<String, Object>> rows = cypher.read(
                "MATCH (m:ExtractedMention {tenant_id: $tenant, mention_id: $mention}) "
                        + "-[r:RESOLVES_TO {active: true}]->(e:Entity) RETURN e.id AS id",
                Map.of("tenant", TENANT, "mention", mention));
        return rows.isEmpty() ? null : String.valueOf(rows.getFirst().get("id"));
    }

    private static long count(String query) {
        return count(query, Map.of("tenant", TENANT));
    }

    private static long count(String query, Map<String, Object> params) {
        List<Map<String, Object>> rows = cypher.read(query, params);
        return rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("c")).longValue();
    }

    private static String mention(String suffix) {
        return "mention-589-" + suffix;
    }

    private static String entity(String suffix) {
        return "entity-589-" + suffix;
    }

    private enum Mode { NORMAL, COMMIT_THEN_TIMEOUT, COMMIT_THEN_ERROR, THROW_TIMEOUT }

    private static final class TestWriter implements ResolutionWriteClient {
        private final Mode mode;
        private int calls;

        private TestWriter(Mode mode) {
            this.mode = mode;
        }

        @Override
        public void upsertResolution(ResolutionDecision decision) {
            calls++;
            if (mode == Mode.THROW_TIMEOUT) {
                throw new RetryableProcessingException("simulated timeout before commit");
            }
            graph.upsertResolution(decision);
            if (mode == Mode.COMMIT_THEN_TIMEOUT && calls == 1) {
                throw new RetryableProcessingException("response lost after commit");
            }
            if (mode == Mode.COMMIT_THEN_ERROR && calls == 1) {
                throw new AssertionError("simulated worker death after commit");
            }
        }
    }

    private record SessionCypher(Driver driver) implements CypherExecutor {
        @Override
        public List<Map<String, Object>> read(String statement, Map<String, Object> params) {
            try (Session session = driver.session()) {
                return session.executeRead(tx -> {
                    var result = tx.run(statement, params);
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (result.hasNext()) {
                        rows.add(result.next().asMap());
                    }
                    return rows;
                });
            }
        }

        @Override
        public void write(String statement, Map<String, Object> params) {
            try (Session session = driver.session()) {
                session.executeWrite(tx -> {
                    tx.run(statement, params).consume();
                    return null;
                });
            }
        }

        @Override
        public List<Map<String, Object>> writeReturning(String statement, Map<String, Object> params) {
            try (Session session = driver.session()) {
                return session.executeWrite(tx -> {
                    var result = tx.run(statement, params);
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (result.hasNext()) {
                        rows.add(result.next().asMap());
                    }
                    return rows;
                });
            }
        }
    }
}
