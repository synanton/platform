package org.synanton.relix.identity;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SourceFenceReconcilerTest {

    private static final SourceFenceKey KEY = new SourceFenceKey("tenant-a", "source-a");
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final FakeAuthority authority = new FakeAuthority();
    private final FakeStore store = new FakeStore();

    @Test
    void initializesMissingFenceFromCassandra() {
        authority.value = 3L;

        SourceFenceReconciliationResult result = reconciler().reconcile(KEY);

        assertThat(result.status()).isEqualTo(SourceFenceStatus.INITIALIZED);
        assertThat(store.generation).isEqualTo(3L);
        assertThat(result.neo4jGeneration()).isEqualTo(3L);
    }

    @Test
    void advancesMonotonicallyAndNeverLowers() {
        authority.value = 3L;
        store.generation = 1L;

        assertThat(reconciler().reconcile(KEY).status()).isEqualTo(SourceFenceStatus.ADVANCED);
        assertThat(store.generation).isEqualTo(3L);

        authority.value = 2L;
        SourceFenceReconciliationResult divergent = reconciler().reconcile(KEY);
        assertThat(divergent.status()).isEqualTo(SourceFenceStatus.DIVERGENT);
        assertThat(store.generation).isEqualTo(3L);
    }

    @Test
    void convergesWhenEqual() {
        authority.value = 3L;
        store.generation = 3L;

        assertThat(reconciler().reconcile(KEY).status()).isEqualTo(SourceFenceStatus.CONVERGED);
        assertThat(store.writes).isZero();
    }

    @Test
    void missingAuthorityFailsClosed() {
        authority.present = false;

        assertThat(reconciler().reconcile(KEY).status())
                .isEqualTo(SourceFenceStatus.MISSING_AUTHORITY);
        assertThat(store.writes).isZero();
    }

    @Test
    void lostResponseRetryConvergesWithoutDuplicateInitialization() {
        authority.value = 3L;

        assertThat(reconciler().reconcile(KEY).status()).isEqualTo(SourceFenceStatus.INITIALIZED);
        assertThat(reconciler().reconcile(KEY).status()).isEqualTo(SourceFenceStatus.CONVERGED);
        assertThat(store.writes).isEqualTo(1);
    }

    @Test
    void staleAuthoritySnapshotIsDistinguishedFromCurrentConvergence() {
        authority.sequence = new long[]{2, 3};

        SourceFenceReconciliationResult result = reconciler().reconcile(KEY);

        assertThat(result.status()).isEqualTo(SourceFenceStatus.STALE_SNAPSHOT);
        assertThat(result.evaluatedGeneration()).isEqualTo(2L);
        assertThat(result.acceptedGeneration()).isEqualTo(3L);
        assertThat(result.neo4jGeneration()).isEqualTo(2L);
    }

    @Test
    void generationSequencesConvergeMonotonically() {
        for (long generation : new long[]{1, 5, 3, 7}) {
            authority.value = generation;
            reconciler().reconcile(KEY);
        }
        assertThat(store.generation).isEqualTo(7L);
    }

    @Test
    void unavailableStoreFailsClosed() {
        authority.value = 3L;
        store.failure = new IllegalStateException("neo4j down");

        assertThat(reconciler().reconcile(KEY).status())
                .isEqualTo(SourceFenceStatus.UNAVAILABLE);
    }

    private SourceFenceReconciler reconciler() {
        return new SourceFenceReconciler(authority, store, CLOCK);
    }

    private static final class FakeAuthority implements SourceGenerationAuthority {
        private boolean present = true;
        private long value = 1L;
        private long[] sequence;
        private int calls;

        @Override
        public Optional<Long> acceptedGeneration(SourceFenceKey key) {
            if (sequence != null) {
                return Optional.of(sequence[Math.min(calls++, sequence.length - 1)]);
            }
            return present ? Optional.of(value) : Optional.empty();
        }
    }

    private static final class FakeStore implements SourceFenceStore {
        private Long generation;
        private int writes;
        private RuntimeException failure;

        @Override
        public Optional<Long> read(SourceFenceKey key) {
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(generation);
        }

        @Override
        public SourceFenceAdvance advance(SourceFenceKey key, long value) {
            if (failure != null) {
                throw failure;
            }
            writes++;
            boolean created = generation == null;
            generation = generation == null ? value : Math.max(generation, value);
            return new SourceFenceAdvance(generation, created);
        }
    }
}
