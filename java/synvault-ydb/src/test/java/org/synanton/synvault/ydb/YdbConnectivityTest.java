package org.synanton.synvault.ydb;

import org.junit.jupiter.api.Test;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterAll;

/**
 * YDB connectivity smoke test (021 pre-req): transport + session + trivial query
 * against the local PoC instance before any adapter code lands.
 *
 * <p>The pinned local-ydb image serves gRPCS only (self-signed CA baked into the
 * container at {@code /ydb_certs/ca.pem}). Tests read it via {@code YDB_CA_PATH}
 * (default {@code /tmp/ydb-ca.pem}); copy it out with
 * {@code docker cp ydb-poc:/ydb_certs/ca.pem /tmp/ydb-ca.pem}.
 */
class YdbConnectivityTest {

    @Test
    void transportAndSessionWork() throws Exception {
        String caPath = System.getenv().getOrDefault("YDB_CA_PATH", "/tmp/ydb-ca.pem");
        byte[] ca = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(caPath));
        try (GrpcTransport transport =
                        GrpcTransport.forConnectionString("grpcs://localhost:2135/local")
                                .withSecureConnection(ca)
                                .build();
                TableClient tableClient = TableClient.newClient(transport).build()) {
            Session session =
                    tableClient
                            .createSession(java.time.Duration.ofSeconds(10))
                            .join()
                            .getValue();
            assertThat(session).isNotNull();
            tech.ydb.core.Status created =
                    session.executeSchemeQuery(
                                    "CREATE TABLE `smoke_conn` (id Utf8, PRIMARY KEY (id));")
                            .join();
            assertThat(created.isSuccess()).isTrue();
            tech.ydb.core.Status dropped =
                    session.executeSchemeQuery("DROP TABLE `smoke_conn`;").join();
            assertThat(dropped.isSuccess()).isTrue();
        }
    }

    @AfterAll
    static void dropSchemas() {
        YdbTestBase.dropAllTracked();
        try (tech.ydb.table.Session s = YdbTestBase.session()) {
            s.executeSchemeQuery("DROP TABLE `smoke_conn`;").join();
        } catch (Exception ignored) {
        }
    }
}
