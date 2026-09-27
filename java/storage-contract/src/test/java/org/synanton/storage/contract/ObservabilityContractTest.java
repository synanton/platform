package org.synanton.storage.contract;

import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityContractTest {

    @Test
    void opStatsPercentiles() {
        OpStats stats = new OpStats();
        for (int i = 1; i <= 100; i++) {
            stats.record(i * 1_000_000L, true);
        }
        assertThat(stats.count()).isEqualTo(100);
        assertThat(stats.errors()).isZero();
        assertThat(stats.p50Ms()).isEqualTo(51.0);
        assertThat(stats.p95Ms()).isEqualTo(96.0);
        assertThat(stats.p99Ms()).isEqualTo(100.0);
        assertThat(stats.avgMs()).isEqualTo(50.5);
    }

    @Test
    void opStatsCountsErrors() {
        OpStats stats = new OpStats();
        stats.record(1_000_000L, true);
        stats.record(2_000_000L, false);
        assertThat(stats.count()).isEqualTo(2);
        assertThat(stats.errors()).isEqualTo(1);
    }

    @Test
    void inMemoryMetricsSnapshotByVerb() {
        InMemoryAdapterMetrics metrics = new InMemoryAdapterMetrics("inmemory@1.0.0");
        try (AdapterMetrics.Timing t = metrics.time(AdapterMetrics.SYNQUEST_SEARCH)) {
            t.success();
        }
        metrics.record(AdapterMetrics.SYNQUEST_SEARCH, 1_000_000L, false);
        AdapterStats snapshot = metrics.snapshot();
        assertThat(snapshot.providerId()).isEqualTo("inmemory@1.0.0");
        assertThat(snapshot.operations().get(AdapterMetrics.SYNQUEST_SEARCH).count()).isEqualTo(2);
        assertThat(snapshot.operations().get(AdapterMetrics.SYNQUEST_SEARCH).errors()).isEqualTo(1);
    }

    @Test
    void freshnessLagPairsCommits() {
        InMemoryFreshnessTracker tracker = new InMemoryFreshnessTracker();
        tracker.recordCommit("r1", 1_000L);
        tracker.recordCommit("r2", 2_000L);
        tracker.recordVisible("r1", 1_250L);
        FreshnessLag lag = tracker.lag();
        assertThat(lag.lastLagMs()).isEqualTo(Optional.of(250L));
        assertThat(lag.maxLagMs()).isEqualTo(Optional.of(250L));
        assertThat(lag.pendingCommits()).isEqualTo(1);
        tracker.recordVisible("r2", 2_100L);
        assertThat(tracker.lag().pendingCommits()).isZero();
    }

    @Test
    void freshnessIgnoresUnknownRevision() {
        InMemoryFreshnessTracker tracker = new InMemoryFreshnessTracker();
        tracker.recordVisible("ghost", 5_000L);
        assertThat(tracker.lag().lastLagMs()).isEmpty();
    }

    @Test
    void activeProvidersSnapshotShape() {
        ActiveProviders providers = ActiveProviders.of("cassandra@1.0.0", "inmemory@1.0.0", "inmemory@1.0.0", "inmemory@1.0.0");
        assertThat(providers.describe()).contains("synvault=cassandra@1.0.0");
    }
}
