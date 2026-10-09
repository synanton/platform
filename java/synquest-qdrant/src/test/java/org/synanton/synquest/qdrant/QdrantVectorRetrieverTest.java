package org.synanton.synquest.qdrant;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.synanton.storage.testkit.VectorRetrieverContract;
import org.synanton.synquest.api.VectorProjection;
import org.synanton.synquest.api.VectorRetriever;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SYN-VECTOR-001 B3.2: Qdrant adapter behind the {@link VectorRetrieverContract}.
 * Single container shared per class; one collection per test.
 */
@Testcontainers
class QdrantVectorRetrieverTest extends VectorRetrieverContract {

    @Container
    static final GenericContainer<?> QDRANT =
            new GenericContainer<>("qdrant/qdrant:latest")
                    .withExposedPorts(6333, 6334)
                    .waitingFor(
                            Wait.forHttp("/readyz")
                                    .forPort(6333)
                                    .withStartupTimeout(Duration.ofMinutes(2)));

    private static QdrantClient client;

    private static synchronized QdrantClient client() {
        if (client == null) {
            // Plaintext: test container has no TLS certificates.
            QdrantGrpcClient grpc = QdrantGrpcClient.newBuilder(
                            QDRANT.getHost(), QDRANT.getMappedPort(6334), false)
                    .build();
            client = new QdrantClient(grpc);
        }
        return client;
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                throw new IllegalStateException("qdrant client teardown failed", e);
            }
        }
    }

    private String collection;

    private QdrantVectorRetriever adapter() {
        if (collection == null) {
            collection = "t_vec_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        return new QdrantVectorRetriever(client(), collection, 2);
    }

    @Override
    protected VectorRetriever newRetriever() {
        return adapter();
    }

    @Override
    protected void seed(List<VectorProjection> projections) {
        adapter()
                .upsert(projections)
                .toCompletableFuture()
                .join();
    }
}
