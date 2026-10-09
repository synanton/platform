package org.synanton.synquest.milvus;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.synanton.storage.testkit.VectorRetrieverContract;
import org.synanton.synquest.api.VectorProjection;
import org.synanton.synquest.api.VectorRetriever;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SYN-VECTOR-001 B3.1: Milvus adapter behind the {@link VectorRetrieverContract}.
 * Standalone trio (etcd + minio + milvus) shared per class; one collection per test.
 */
@Testcontainers
class MilvusVectorRetrieverTest extends VectorRetrieverContract {

    static final Network NETWORK = Network.newNetwork();

    @Container
    static final GenericContainer<?> ETCD =
            new GenericContainer<>("quay.io/coreos/etcd:v3.5.18")
                    .withNetwork(NETWORK)
                    .withNetworkAliases("etcd")
                    .withCommand(
                            "etcd",
                            "-advertise-client-urls=http://127.0.0.1:2379",
                            "-listen-client-urls=http://0.0.0.0:2379",
                            "--data-dir=/etcd");

    @Container
    static final GenericContainer<?> MINIO =
            new GenericContainer<>("minio/minio:latest")
                    .withNetwork(NETWORK)
                    .withNetworkAliases("minio")
                    .withEnv("MINIO_ROOT_USER", "minioadmin")
                    .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
                    .withCommand("server", "/minio_data")
                    .withExposedPorts(9000);

    @Container
    static final GenericContainer<?> MILVUS =
            new GenericContainer<>("milvusdb/milvus:latest")
                    .withNetwork(NETWORK)
                    .withEnv("ETCD_ENDPOINTS", "etcd:2379")
                    .withEnv("MINIO_ADDRESS", "minio:9000")
                    .withExposedPorts(19530, 9091)
                    .withCommand("milvus", "run", "standalone")
                    .waitingFor(
                            Wait.forHttp("/healthz")
                                    .forPort(9091)
                                    .withStartupTimeout(Duration.ofMinutes(3)))
                    .dependsOn(ETCD, MINIO);

    private static MilvusServiceClient client;

    private static synchronized MilvusServiceClient client() {
        if (client == null) {
            client =
                    new MilvusServiceClient(
                            ConnectParam.newBuilder()
                                    .withHost(MILVUS.getHost())
                                    .withPort(MILVUS.getMappedPort(19530))
                                    .build());
        }
        return client;
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    private String collection;

    private MilvusVectorRetriever adapter() {
        if (collection == null) {
            collection = "t_vec_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        return new MilvusVectorRetriever(client(), collection, 2);
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
