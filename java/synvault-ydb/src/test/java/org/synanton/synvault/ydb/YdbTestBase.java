package org.synanton.synvault.ydb;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.UUID;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;

/**
 * Live-YDB fixture (021): shared transport/client per JVM, one table set per test
 * class (random prefix — no DDL churn, parallel-safe). Row isolation additionally
 * uses per-store tenant namespaces. CA handling mirrors the connectivity test.
 */
abstract class YdbTestBase {

    private static volatile GrpcTransport transport;
    private static volatile TableClient tableClient;

    protected static synchronized void ensureStarted() {
        if (tableClient != null) {
            return;
        }
        try {
            String caPath = System.getenv().getOrDefault("YDB_CA_PATH", "/tmp/ydb-ca.pem");
            byte[] ca = Files.readAllBytes(Paths.get(caPath));
            transport =
                    GrpcTransport.forConnectionString("grpcs://localhost:2135/local")
                            .withSecureConnection(ca)
                            .build();
            tableClient = TableClient.newClient(transport).build();
        } catch (Exception e) {
            throw new IllegalStateException("YDB test fixture failed to start", e);
        }
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    if (tableClient != null) {
                                        tableClient.close();
                                    }
                                    if (transport != null) {
                                        transport.close();
                                    }
                                }));
    }

    protected static TableClient client() {
        ensureStarted();
        return tableClient;
    }

    protected static Session session() {
        return client()
                .createSession(Duration.ofSeconds(10))
                .join()
                .getValue();
    }

    protected static void scheme(String yql) {
        try (Session session = session()) {
            tech.ydb.core.Status status = session.executeSchemeQuery(yql).join();
            if (!status.isSuccess()) {
                throw new IllegalStateException("DDL failed: " + yql + " -> " + status);
            }
        }
    }

    protected static String randomPrefix() {
        return "poc" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
