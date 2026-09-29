package org.synanton.synquest.ydb;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;
import tech.ydb.table.query.BulkUpsertData;
import tech.ydb.table.settings.BulkUpsertSettings;
import tech.ydb.table.values.ListValue;
import tech.ydb.table.values.PrimitiveValue;
import tech.ydb.table.values.StructValue;
import tech.ydb.table.values.Value;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2.1 probe (gated -Dydb.bulkprobe=true): BulkUpsert vs YQL-tx on a
 * no-index table. Targets the bench2 container (2145) unless YDB_PROBE_PORT
 * overrides. Table has NO secondary indexes — isolates the write path from
 * index maintenance.
 */
@EnabledIfSystemProperty(named = "ydb.bulkprobe", matches = "true")
class BulkUpsertProbe {

    private static final int ROWS = 100;

    private static TableClient client() throws Exception {        String port = System.getenv().getOrDefault("YDB_PROBE_PORT", "2145");
        String caPath =
                System.getenv().getOrDefault(
                        "YDB_PROBE_CA",
                        System.getProperty("ydb.ca.path", "/tmp/ydb-ca-bench2.pem"));
        byte[] ca = Files.readAllBytes(Paths.get(caPath));
        GrpcTransport transport =
                GrpcTransport.forConnectionString("grpcs://localhost:" + port + "/local")
                        .withSecureConnection(ca)
                        .build();
        return TableClient.newClient(transport).build();
    }

    @Test
    void bulkUpsert100RowsNoIndex() throws Exception {
        TableClient client = client();
        try {
            String table = "t_probe_bulk";
            try (Session session = client.createSession(Duration.ofSeconds(30)).join().getValue()) {
                session.executeSchemeQuery("DROP TABLE `" + table + "`;").join();
                var created =
                        session.executeSchemeQuery(
                                        "CREATE TABLE `" + table + "` (tenant_id Utf8 NOT NULL,"
                                                + " chunk_id Utf8 NOT NULL, payload Utf8,"
                                                + " PRIMARY KEY (tenant_id, chunk_id));")
                                .join();
                assertThat(created.isSuccess()).isTrue();

                List<Value<?>> rows = new ArrayList<>(ROWS);
                for (int i = 0; i < ROWS; i++) {
                    Map<String, Value<?>> m = new LinkedHashMap<>();
                    m.put("tenant_id", PrimitiveValue.newText("tenant_07"));
                    m.put("chunk_id", PrimitiveValue.newText("bulk-" + i));
                    m.put("payload", PrimitiveValue.newText("payload-" + i));
                    rows.add(StructValue.of(m));
                }
                BulkUpsertData data =
                        new BulkUpsertData(
                                ListValue.of(rows.toArray(new Value<?>[0])));
                long t0 = System.nanoTime();
                // BulkUpsert needs the fully-qualified path (no session-db resolution).
                var status =
                        session
                                .executeBulkUpsert("/local/" + table, data, new BulkUpsertSettings())
                                .join();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertThat(status.isSuccess()).as("bulk status: " + status).isTrue();
                System.out.println(
                        "BULK-PROBE rows=" + ROWS + " ms=" + ms + " rows_per_s="
                                + String.format("%.1f", ROWS / (ms / 1000.0)));

                // Durability: read back immediately — buffered-but-unwritten
                // would pass timing and fail here.
                var count =
                        session
                                .executeDataQuery(
                                        "SELECT COUNT(*) AS n FROM `" + table + "`;",
                                        tech.ydb.table.transaction.TxControl.staleRo()
                                                .setCommitTx(true),
                                        tech.ydb.table.query.Params.empty(),
                                        new tech.ydb.table.settings.ExecuteDataQuerySettings())
                                .join()
                                .getValue()
                                .getResultSet(0);
                assertThat(count.next()).isTrue();
                assertThat(count.getColumn("n").getUint64())
                        .as("all rows durable immediately after return")
                        .isEqualTo(ROWS);
                var sample =
                        session
                                .executeDataQuery(
                                        "SELECT payload FROM `" + table
                                                + "` WHERE tenant_id='tenant_07' AND chunk_id='bulk-42';",
                                        tech.ydb.table.transaction.TxControl.staleRo()
                                                .setCommitTx(true),
                                        tech.ydb.table.query.Params.empty(),
                                        new tech.ydb.table.settings.ExecuteDataQuerySettings())
                                .join()
                                .getValue()
                                .getResultSet(0);
                assertThat(sample.next()).isTrue();
                assertThat(sample.getColumn("payload").getText()).isEqualTo("payload-42");
                System.out.println("BULK-PROBE durability verified (count + content)");
            }
        } finally {
            client.close();
        }
    }

    @Test
    void bulkUpsertScalesTo1k() throws Exception {
        TableClient client = client();
        try {
            String table = "t_probe_bulk_1k";
            try (Session session = client.createSession(Duration.ofSeconds(30)).join().getValue()) {
                session.executeSchemeQuery("DROP TABLE `" + table + "`;").join();
                var created =
                        session.executeSchemeQuery(
                                        "CREATE TABLE `" + table + "` (tenant_id Utf8 NOT NULL,"
                                                + " chunk_id Utf8 NOT NULL, payload Utf8,"
                                                + " PRIMARY KEY (tenant_id, chunk_id));")
                                .join();
                assertThat(created.isSuccess()).isTrue();

                List<Value<?>> rows = new ArrayList<>(1000);
                for (int i = 0; i < 1000; i++) {
                    Map<String, Value<?>> m = new LinkedHashMap<>();
                    m.put("tenant_id", PrimitiveValue.newText("tenant_07"));
                    m.put("chunk_id", PrimitiveValue.newText("bulk1k-" + i));
                    m.put("payload", PrimitiveValue.newText("payload-" + i));
                    rows.add(StructValue.of(m));
                }
                long t0 = System.nanoTime();
                var status =
                        session
                                .executeBulkUpsert(
                                        "/local/" + table,
                                        new BulkUpsertData(
                                                ListValue.of(rows.toArray(new Value<?>[0]))),
                                        new BulkUpsertSettings())
                                .join();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertThat(status.isSuccess()).as("bulk status: " + status).isTrue();
                System.out.println(
                        "BULK-PROBE-1K rows=1000 ms=" + ms + " rows_per_s="
                                + String.format("%.1f", 1000 / Math.max(ms, 1) * 1000.0));
            }
        } finally {
            client.close();
        }
    }
}
