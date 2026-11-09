package org.synanton.ingestioncache.claim;

import com.datastax.oss.driver.api.core.CqlSession;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.config.SchemaInstaller;
import org.synanton.ingestioncache.domain.ResolutionWorkRow;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** PR #4d.1: exactly one worker owns a claim; expired ownership is recoverable. */
class ClaimConcurrencyTest {

    private static CassandraContainer cassandra;
    private static CqlSession session;
    private static IngestionCacheClient client;

    @BeforeAll
    static void startInfra() {
        cassandra = new CassandraContainer(DockerImageName.parse("cassandra:4.1"));
        cassandra.start();
        InetSocketAddress contact =
                new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042));
        try (CqlSession admin = CqlSession.builder()
                .addContactPoint(contact).withLocalDatacenter("datacenter1").build()) {
            admin.execute("CREATE KEYSPACE IF NOT EXISTS ingestion_cache WITH replication = "
                    + "{'class':'SimpleStrategy','replication_factor':1}");
        }
        session = CqlSession.builder().addContactPoint(contact)
                .withLocalDatacenter("datacenter1").withKeyspace("ingestion_cache").build();
        SchemaInstaller.install(session);
        client = new IngestionCacheClient(session);
    }

    @AfterAll
    static void stopInfra() {
        if (session != null) {
            session.close();
        }
        if (cassandra != null) {
            cassandra.stop();
        }
    }

    @Test
    void tenWorkersClaimSameItemExactlyOnce() throws Exception {
        ResolutionWorkRow work = work();
        client.insertResolutionWork(work);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> claims = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < 10; i++) {
            String owner = "worker-" + i;
            claims.add(pool.submit(() -> {
                start.await();
                return client.claimResolutionWork(work, owner, now, Duration.ofMinutes(5));
            }));
        }
        start.countDown();
        int wins = 0;
        for (Future<Boolean> claim : claims) {
            if (claim.get(30, TimeUnit.SECONDS)) {
                wins++;
            }
        }
        pool.shutdownNow();
        assertThat(wins).isEqualTo(1);

        ResolutionWorkRow claimed = client.listResolutionWorkRows(
                work.tenantId(), work.sourceVersionId()).getFirst();
        assertThat(claimed.claimStatus()).isEqualTo("PROCESSING");
        assertThat(claimed.attemptCount()).isEqualTo(1);
        assertThat(claimed.claimOwnerId()).isNotBlank();
    }

    @Test
    void expiredClaimCanBeReclaimedAndReowned() {
        ResolutionWorkRow work = work();
        client.insertResolutionWork(work);
        Instant now = Instant.now();
        assertThat(client.claimResolutionWork(work, "old-worker", now, Duration.ofSeconds(-1)))
                .isTrue();
        ResolutionWorkRow claimed = client.listResolutionWorkRows(
                work.tenantId(), work.sourceVersionId()).getFirst();
        assertThat(claimed.claimStatus()).isEqualTo("PROCESSING");
        assertThat(claimed.leaseExpiresAt()).isBefore(Instant.now());

        assertThat(client.reclaimResolutionWork(claimed, now)).isTrue();
        ResolutionWorkRow reclaimed = client.listResolutionWorkRows(
                work.tenantId(), work.sourceVersionId()).getFirst();
        assertThat(reclaimed.claimStatus()).isEqualTo("PENDING");
        assertThat(reclaimed.attemptCount()).isEqualTo(1);

        assertThat(client.claimResolutionWork(reclaimed, "new-worker", now, Duration.ofMinutes(1)))
                .isTrue();
        ResolutionWorkRow reowned = client.listResolutionWorkRows(
                work.tenantId(), work.sourceVersionId()).getFirst();
        assertThat(reowned.claimOwnerId()).isEqualTo("new-worker");
        assertThat(reowned.attemptCount()).isEqualTo(2);
    }

    private static ResolutionWorkRow work() {
        return new ResolutionWorkRow(
                "tenant-claim", UUID.randomUUID(), "mention-1", "connector", "source", "resource",
                UUID.randomUUID(), 0, 0, 3, 0, 3, "Acme", "Organization", "PENDING", null,
                null, 0, null, null, Instant.now(), null, null, null);
    }
}
