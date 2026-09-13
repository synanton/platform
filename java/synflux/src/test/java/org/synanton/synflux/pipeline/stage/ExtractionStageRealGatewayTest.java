package org.synanton.synflux.pipeline.stage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.synanton.extraction.client.ExtractionClientMetrics;
import org.synanton.extraction.client.ExtractionClientProperties;
import org.synanton.extraction.client.ExtractionFallbackPolicy;
import org.synanton.extraction.client.ExtractionPlaneClient;
import org.synanton.extraction.client.LocalTikaFallbackExtractor;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.adapter.MinioObjectStoreAdapter;
import org.synanton.synvault.config.SynvaultObjectStoreProperties;
import org.synanton.synvault.domain.ContentRef;
import synanton.extraction.v1.DocumentElementType;

import java.io.IOException;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real, cross-repo proof that {@link ExtractionStage} talks to a genuinely live
 * content_extractor {@code extraction-gateway} — not a mock — and gets back a real
 * OpenDataLoader-produced {@code DocumentPayload}. This is the only test in either repo that
 * actually exercises the two services together; everything else (this repo's
 * {@code ExtractionStageGrpcTest}, content_extractor's own gateway/adapter tests) proves one
 * side in isolation.
 *
 * <p><b>Manual run only</b> — excluded from the default {@code test} task via {@code @Disabled}
 * because it needs Docker (via the plain CLI, not Testcontainers-Java — this environment's
 * {@code docker-java} client sends a hardcoded old API version that a sufficiently new Docker
 * Engine rejects; verified failing with both testcontainers 1.21.3 and 2.0.5) and a sibling
 * {@code content_extractor} checkout one directory up from this repo.
 *
 * <p>The real gateway's stdout/stderr are captured at {@code /tmp/extraction-gateway-test.log}
 * for inspection (confirmed 2026-09-12: real OpenDataLoader output, e.g.
 * {@code "org.opendataloader.pdf.json.JsonWriter : Created .../<name>.json"}, appears there).
 *
 * <p>To run: remove {@code @Disabled} (or invoke the method directly from an IDE), then:
 * <pre>{@code
 * ./gradlew :java:synflux:test --tests "*ExtractionStageRealGatewayTest*"
 * }</pre>
 * Setup (Postgres, MinIO, building and starting content_extractor's gateway) all happens
 * inside the test itself; no manual container/process management is required beyond having
 * Docker and a sibling {@code content_extractor} checkout with its Gradle wrapper available.
 */
@Disabled("Manual only - requires Docker CLI and a sibling content_extractor checkout; see class javadoc")
class ExtractionStageRealGatewayTest {

    // Gradle's Test task working directory defaults to the subproject dir (java/synflux/),
    // so this needs three levels up to reach the synanton/ workspace root: synflux -> java
    // -> platform -> synanton.
    private static final Path CONTENT_EXTRACTOR_DIR = Path.of("../../../content_extractor").toAbsolutePath().normalize();
    private static final int PG_PORT = 15432;
    private static final int MINIO_PORT = 19000;
    private static final int GATEWAY_GRPC_PORT = 19091;
    private static final int GATEWAY_HTTP_PORT = 19092;
    private static final String HOT_BUCKET = "synanton-hot";

    private String pgContainerId;
    private String minioContainerId;
    private Process gatewayProcess;

    @AfterEach
    void tearDown() {
        if (gatewayProcess != null) {
            gatewayProcess.destroyForcibly();
        }
        removeContainerQuietly(pgContainerId);
        removeContainerQuietly(minioContainerId);
    }

    @Test
    void shouldExtractRealPdfThroughLiveContentExtractorGateway() throws Exception {
        assertThat(Files.isDirectory(CONTENT_EXTRACTOR_DIR))
                .as("content_extractor sibling checkout at %s", CONTENT_EXTRACTOR_DIR)
                .isTrue();

        pgContainerId = runDocker(
                "run", "-d", "--rm", "-p", PG_PORT + ":5432",
                "-e", "POSTGRES_DB=extraction", "-e", "POSTGRES_USER=extraction",
                "-e", "POSTGRES_PASSWORD=extraction", "postgres:16-alpine");
        minioContainerId = runDocker(
                "run", "-d", "--rm", "-p", MINIO_PORT + ":9000",
                "-e", "MINIO_ROOT_USER=minioadmin", "-e", "MINIO_ROOT_PASSWORD=minioadmin",
                "minio/minio", "server", "/data");
        waitForPort("localhost", PG_PORT, Duration.ofSeconds(30));
        waitForPort("localhost", MINIO_PORT, Duration.ofSeconds(30));

        buildContentExtractorGateway();
        startGateway();
        waitForGatewayHealth(Duration.ofSeconds(60));

        ExtractionStage stage = buildStage();
        AcquiredDocument doc = buildAcquiredDocumentFromRealFixture();

        ParsedDocument parsed = stage.apply(doc, new StageContext("demo-tenant", "real-gateway-test", null));

        assertThat(parsed.documentPayload())
                .as("real content_extractor gateway must return a structured payload, not a fallback")
                .isNotNull();
        assertThat(parsed.documentPayload().getElementsList())
                .as("OpenDataLoader must have found at least one real element")
                .isNotEmpty();
        assertThat(parsed.documentPayload().getElementsList())
                .extracting(el -> el.getType())
                .contains(DocumentElementType.ELEMENT_HEADING);
        assertThat(parsed.text()).contains("Quarterly Report");
    }

    private ExtractionStage buildStage() {
        ExtractionClientProperties props = new ExtractionClientProperties(
                true, "localhost:" + GATEWAY_GRPC_PORT, "sync", 30, 1, "local-tika", "NORMAL");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExtractionPlaneClient client = new ExtractionPlaneClient(props, new ExtractionClientMetrics(registry));
        MinioObjectStoreAdapter objectStore = new MinioObjectStoreAdapter(
                new SynvaultObjectStoreProperties(
                        "http://localhost:" + MINIO_PORT, "us-east-1", true, "minioadmin", "minioadmin", HOT_BUCKET));
        return new ExtractionStage(
                client, new LocalTikaFallbackExtractor(), ExtractionFallbackPolicy.STRUCTURED_REQUIRED,
                new ExtractionClientMetrics(registry), objectStore, HOT_BUCKET);
    }

    private AcquiredDocument buildAcquiredDocumentFromRealFixture() throws Exception {
        Path fixture = CONTENT_EXTRACTOR_DIR.resolve(
                "java/adapter-document-pdf/src/test/resources/fixtures/quarterly-report.pdf");
        assertThat(Files.exists(fixture)).as("content_extractor's real PDF fixture at %s", fixture).isTrue();
        byte[] bytes = Files.readAllBytes(fixture);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        UUID contentRefId = UUID.randomUUID();
        return new AcquiredDocument(
                new ContentRef("file", fixture.toUri().toString(), "application/pdf", bytes.length, Instant.now()),
                bytes, sha256, "application/pdf", fixture.toUri().toString(), contentRefId);
    }

    private void buildContentExtractorGateway() throws IOException, InterruptedException {
        runProcess(CONTENT_EXTRACTOR_DIR, Duration.ofMinutes(5),
                "./gradlew", ":java:extraction-gateway:bootJar", "-q");
    }

    private void startGateway() throws IOException {
        Path jar = findGatewayJar();
        ProcessBuilder pb = new ProcessBuilder(
                "java", "-jar", jar.toString());
        pb.environment().put("EXTRACTION_DB_URL", "jdbc:postgresql://localhost:" + PG_PORT + "/extraction");
        pb.environment().put("EXTRACTION_DB_USER", "extraction");
        pb.environment().put("EXTRACTION_DB_PASSWORD", "extraction");
        pb.environment().put("EXTRACTION_GATEWAY_GRPC_PORT", String.valueOf(GATEWAY_GRPC_PORT));
        pb.environment().put("EXTRACTION_GATEWAY_HTTP_PORT", String.valueOf(GATEWAY_HTTP_PORT));
        pb.environment().put("EXTRACTION_OBJECTSTORE_ENDPOINT", "http://localhost:" + MINIO_PORT);
        pb.environment().put("EXTRACTION_OBJECTSTORE_ACCESS_KEY", "minioadmin");
        pb.environment().put("EXTRACTION_OBJECTSTORE_SECRET_KEY", "minioadmin");
        pb.redirectOutput(new java.io.File("/tmp/extraction-gateway-test.log"));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(new java.io.File("/tmp/extraction-gateway-test.log")));
        gatewayProcess = pb.start();
    }

    private Path findGatewayJar() throws IOException {
        Path libDir = CONTENT_EXTRACTOR_DIR.resolve("java/extraction-gateway/build/libs");
        try (var files = Files.list(libDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No jar built in " + libDir));
        }
    }

    private void waitForGatewayHealth(Duration timeout) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + GATEWAY_HTTP_PORT + "/actuator/health"))
                .timeout(Duration.ofSeconds(2))
                .build();
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"")) {
                    return;
                }
            } catch (Exception ignored) {
                // gateway not up yet
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("content_extractor gateway did not become healthy within " + timeout);
    }

    private static void waitForPort(String host, int port, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try (Socket socket = new Socket(host, port)) {
                return;
            } catch (IOException e) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("Port " + host + ":" + port + " did not open within " + timeout);
    }

    private static String runDocker(String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add("docker");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("docker " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }

    private static void runProcess(Path workingDir, Duration timeout, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed:\n" + output);
        }
    }

    private static void removeContainerQuietly(String containerId) {
        if (containerId == null || containerId.isBlank()) {
            return;
        }
        try {
            new ProcessBuilder("docker", "rm", "-f", containerId).start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
