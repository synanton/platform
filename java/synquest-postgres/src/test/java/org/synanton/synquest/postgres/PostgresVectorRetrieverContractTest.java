package org.synanton.synquest.postgres;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.synanton.storage.testkit.VectorRetrieverContract;
import org.synanton.synquest.api.VectorProjection;
import org.synanton.synquest.api.VectorRetriever;

/**
 * SYN-VECTOR-001 B3.4: the existing PG vector path, exposed explicitly through
 * {@link VectorRetriever}, against the shared contract. Reference frame:
 * identical numbers to the current PG vector path by construction (B1.3
 * through-search delegation — this suite pins it to the contract).
 */
class PostgresVectorRetrieverContractTest extends VectorRetrieverContract {

    private PostgresSynquestEngine engine;

    @BeforeEach
    void freshEngineAndCleanState() {
        truncateState();
        engine = QuestPostgresFixture.newEngine();
    }

    private static void truncateState() {
        try (var connection = QuestPostgresFixture.appConnection();
                var stmt = connection.createStatement()) {
            stmt.execute("TRUNCATE chunks, quest_generations");
        } catch (Exception e) {
            throw new IllegalStateException("truncate failed", e);
        }
    }

    @Override
    protected VectorRetriever newRetriever() {
        return new PostgresVectorRetriever(engine);
    }

    @Override
    protected void seed(List<VectorProjection> projections) {
        engine.upsert(projections).toCompletableFuture().join();
    }
}
