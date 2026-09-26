package org.synanton.synquest.ydb;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;

/** Shared live-YDB fixture for 024B tests (mirrors the synvault-ydb fixture). */
abstract class YdbSearchTestBase {

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
        return client().createSession(Duration.ofSeconds(10)).join().getValue();
    }

    protected static String randomPrefix() {
        return "q" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
