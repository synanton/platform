package org.synanton.synquest.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.eventing.contract.ActorKind;
import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.EventEnvelopeBuilder;
import org.synanton.eventing.contract.EventTypeDeclaration;
import org.synanton.eventing.contract.EventTypeRegistry;
import org.synanton.eventing.contract.Producer;
import org.synanton.eventing.contract.ReplayClass;
import org.synanton.eventing.contract.SecurityMetadata;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.SourceIdentityRow;
import org.synanton.ingestioncache.domain.SourceOutboxRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Migration 3, step 6: the outbox holds rows written before the typed-envelope change
 * (historically a {@code Map} payload) alongside rows written after it (a typed
 * {@code SourcePublished} record). The search projection consumer must not care about
 * {@code event_payload_json}'s shape — it reads only the outbox metadata and rebuilds
 * the projection from Cassandra — so a legacy row must not abort the polling loop and
 * strand its neighbours.
 *
 * <p>This drives the real {@link IngestionCacheClient} writer and reader against a real
 * Cassandra 4.1 instance, then runs the real {@link SearchProjectionConsumer}.
 */
class SearchProjectionLegacyCompatTest {

    private static final String TENANT = "tenant-legacy-compat";
    private static final String CONNECTOR = "upload";
    private static final String SOURCE = "legacy-source";
    private static final String RESOURCE = "docs/manual.md";

    private static final EventTypeRegistry REGISTRY = new EventTypeRegistry(List.of(
            new EventTypeDeclaration("SourcePublished", 1, ReplayClass.REPLAY_SAFE, Optional.empty())));

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static CassandraContainer cassandra;
    private static CqlSession session;
    private static IngestionCacheClient cache;

    @BeforeAll
    static void startCassandra() {
        cassandra = new CassandraContainer(DockerImageName.parse("cassandra:4.1"));
        cassandra.start();
        InetSocketAddress contact = new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042));
        try (CqlSession admin = CqlSession.builder()
                .addContactPoint(contact).withLocalDatacenter("datacenter1").build()) {
            admin.execute("CREATE KEYSPACE IF NOT EXISTS ingestion_cache WITH replication = "
                    + "{'class':'SimpleStrategy','replication_factor':1}");
        }
        session = CqlSession.builder().addContactPoint(contact)
                .withLocalDatacenter("datacenter1").withKeyspace("ingestion_cache").build();
        SchemaInstaller.install(session);
        cache = new IngestionCacheClient(session);

        cache.createSourceIfAbsent(new SourceRow(TENANT, SOURCE, CONNECTOR, "filesystem", "fixture://legacy",
                Set.of(), "ACTIVE", Instant.parse("2026-11-01T00:00:00Z"), null));
        cache.createSourceIdentityIfAbsent(new SourceIdentityRow(TENANT, CONNECTOR, SOURCE, RESOURCE));
    }

    @AfterAll
    static void stopCassandra() {
        if (session != null) {
            session.close();
        }
        if (cassandra != null) {
            cassandra.stop();
        }
    }

    @Test
    void consumerProjectsLegacyAndTypedEnvelopeRowsWithoutParsingTheirPayloads() throws Exception {
        Instant observedAt = Instant.parse("2026-11-06T12:00:00Z");
        UUID legacyVersionId = UUID.randomUUID();
        UUID typedVersionId = UUID.randomUUID();

        String legacyPayload = legacyPayloadJson(legacyVersionId, observedAt);
        String typedPayload = typedEnvelopeJson(typedVersionId, observedAt);

        insertVersion(legacyVersionId, observedAt, legacyPayload);
        insertVersion(typedVersionId, observedAt.plusSeconds(1), typedPayload);

        List<SourceOutboxRow> pending = cache.listUnpublishedSourceOutbox(TENANT, 200, Instant.now());
        assertThat(pending).extracting(SourceOutboxRow::payloadJson)
                .containsExactlyInAnyOrder(legacyPayload, typedPayload);

        SearchService search = mock(SearchService.class);
        SearchProjectionConsumer consumer = new SearchProjectionConsumer(cache, search, TENANT, 200, true);
        consumer.validateProjectionConfig();

        consumer.projectNewSourceVersions();

        verify(search).projectContentRefs(eq(TENANT), any());
        assertThat(cache.listUnpublishedSourceOutbox(TENANT, 200, Instant.now())).isEmpty();
        assertThat(cache.listSourceOutbox(TENANT, CONNECTOR, SOURCE, RESOURCE))
                .allMatch(SourceOutboxRow::published);
    }

    private static void insertVersion(UUID versionId, Instant observedAt, String payloadJson) {
        UUID contentRefId = UUID.randomUUID();
        SourceVersionRow version = new SourceVersionRow(
                TENANT, CONNECTOR, SOURCE, RESOURCE, versionId, "sha256:" + versionId, "rev-" + versionId,
                observedAt, contentRefId, UUID.randomUUID(), "");
        SourceOutboxRow event = new SourceOutboxRow(
                TENANT, CONNECTOR, SOURCE, RESOURCE, UUID.randomUUID(), "SourcePublished", versionId, null,
                payloadJson, observedAt, false);
        assertThat(cache.insertSourceVersionAndOutboxIfAbsent(version, event)).isTrue();
    }

    /**
     * Pre-envelope shape: a bare payload with no {@code Event} wrapper. The consumer
     * never parses {@code event_payload_json}, so this must project exactly like a
     * wrapped row — proving legacy rows cannot strand the polling loop.
     */
    private static String legacyPayloadJson(UUID versionId, Instant observedAt) throws Exception {
        return MAPPER.writeValueAsString(basePayload(versionId, observedAt));
    }

    /**
     * Migration-3 shape: the typed {@code SourcePublished} record serializes to these
     * exact wire keys (locked by synflux's {@code IngestionEventConfigTest}); this test
     * reproduces them as a map because the record lives in the synflux module, whose jar
     * is not published for test consumption.
     */
    private static String typedEnvelopeJson(UUID versionId, Instant observedAt) throws Exception {
        Map<String, Object> payload = basePayload(versionId, observedAt);
        Event<Map<String, Object>> envelope = EventEnvelopeBuilder.build(
                UUID.randomUUID(), "SourcePublished", 1, observedAt, TENANT, payload,
                new Producer("synflux", "1"), UUID.randomUUID(), Optional.empty(),
                ReplayClass.REPLAY_SAFE, security(), REGISTRY);
        return MAPPER.writeValueAsString(envelope);
    }

    private static Map<String, Object> basePayload(UUID versionId, Instant observedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_identity", Map.of(
                "tenant_id", TENANT, "connector_id", CONNECTOR, "source_id", SOURCE, "resource_id", RESOURCE));
        payload.put("version_id", versionId.toString());
        payload.put("content_digest", "sha256:" + versionId);
        payload.put("content_ref_id", UUID.randomUUID().toString());
        payload.put("observed_at", observedAt.toString());
        payload.put("run_id", UUID.randomUUID().toString());
        payload.put("operation_id", UUID.randomUUID().toString());
        payload.put("workflow_id", "wf-" + versionId);
        return payload;
    }

    private static SecurityMetadata security() {
        return new SecurityMetadata(ActorKind.SERVICE, "synflux", "UNCLASSIFIED", 1, List.of());
    }
}
