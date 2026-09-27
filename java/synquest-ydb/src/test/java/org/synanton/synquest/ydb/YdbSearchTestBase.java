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

    private static final java.util.List<String> TRACKED_PREFIXES =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Schema creation that registers for @AfterAll cleanup (no silent table leaks). */
    protected static String trackedSchema(String prefix, int embeddingDim) {
        YdbSearchSchema.ensureSchema(client(), prefix, embeddingDim);
        TRACKED_PREFIXES.add(prefix);
        return prefix;
    }

    /**
     * Drops all tracked schemas. Called explicitly from each test class's own
     * {@code @AfterAll} — inherited {@code @AfterAll} in this base never runs
     * because no test class extends it (verified 2026-09-27: silent no-cleanup).
     */
    public static void dropAllTracked() {
        for (String prefix : TRACKED_PREFIXES) {
            YdbSearchSchema.dropSchema(client(), prefix);
        }
        TRACKED_PREFIXES.clear();
    }

    protected static String randomPrefix() {
        return "q" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
