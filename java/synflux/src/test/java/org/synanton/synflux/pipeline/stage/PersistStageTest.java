package org.synanton.synflux.pipeline.stage;

import org.junit.jupiter.api.Test;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.KnowledgeProcessingRunRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.extraction.v1.CanonicalTextRange;
import org.synanton.extraction.v1.DocumentClass;
import org.synanton.extraction.v1.DocumentElement;
import org.synanton.extraction.v1.DocumentElementType;
import org.synanton.extraction.v1.DocumentIdentity;
import org.synanton.extraction.v1.DocumentPayload;
import org.synanton.extraction.v1.DocumentProjection;
import org.synanton.extraction.v1.ExecutionLink;
import org.synanton.extraction.v1.ExecutionLinkKind;
import org.synanton.extraction.v1.ExecutionOutcome;
import org.synanton.extraction.v1.ExtractionExecution;
import org.synanton.extraction.v1.ExtractionProvenance;
import org.synanton.extraction.v1.ProjectionOutcome;
import org.synanton.extraction.v1.QualityReport;
import org.synanton.extraction.v1.QualityStatus;
import org.synanton.extraction.v1.SourceDescriptor;
import org.synanton.extraction.v1.SourceLocation;
import org.synanton.synflux.config.SynfluxProperties;
import org.synanton.synflux.domain.AcquiredDocument;
import org.synanton.synflux.domain.ChunkedDocument;
import org.synanton.synflux.domain.ParsedDocument;
import org.synanton.synflux.domain.SemanticChunk;
import org.synanton.synflux.pipeline.StageContext;
import org.synanton.synvault.domain.ContentRef;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersistStageTest {

    private static SynfluxProperties props() {
        return new SynfluxProperties(
                new SynfluxProperties.Ingest(1, 1000, new SynfluxProperties.Ingest.Chunk("semantic-v1", 400, 50)),
                new SynfluxProperties.Pipeline(false, false, false),
                new SynfluxProperties.Enrichment("http://x", "m", 1),
                new SynfluxProperties.Embedding("http://x", "m", 1),
                new SynfluxProperties.Annotation("http://x", "rule-engine", "1.0"),
                new SynfluxProperties.Kafka(1, 1, 1)
        );
    }

    private static ChunkedDocument doc(UUID refId) {
        return doc(refId, parsed(refId, null, Map.of()));
    }

    private static ChunkedDocument doc(UUID refId, ParsedDocument parsed) {
        SemanticChunk chunk = new SemanticChunk(
                "c0", refId.toString(), 0, SemanticChunk.ChunkType.SECTION, "hello", null,
                List.of("Supply chain", "Europe"), "Europe", List.of("e1"), 2, 2, 1, false, Map.of(),
                SemanticChunk.PUBLIC_ONLY, "bb");
        return new ChunkedDocument(parsed, List.of(chunk));
    }

    private static ParsedDocument parsed(UUID refId, DocumentPayload payload,
                                         Map<String, String> descriptor) {
        ContentRef contentRef = new ContentRef("file", "file:///tmp/a.md", "text/markdown", 10, Instant.now());
        AcquiredDocument acquired = new AcquiredDocument(contentRef, new byte[]{1}, "aa", "text/markdown",
                "file:///tmp/a.md", refId, "v1:tenant_id=demo&artifact_id=a1&digest=sha256%3Aaa");
        return new ParsedDocument(acquired, "hello", Map.of(), payload, Map.of(), descriptor);
    }

    private static DocumentPayload canonicalPayload(UUID versionId, ExecutionOutcome outcome,
                                                    QualityReport quality) {
        return DocumentPayload.newBuilder()
                .setMediaType("text/markdown")
                .setFlattenedText("hello")
                .setIdentity(DocumentIdentity.newBuilder()
                        .setDocumentId("doc-1")
                        .setDocumentClass(DocumentClass.DOCUMENT_CLASS_TEXT)
                        .setDocumentVersion("dv-1"))
                .setSource(SourceDescriptor.newBuilder()
                        .setMediaType("text/markdown")
                        .setSourceFormat("markdown")
                        .setSourceDigest("sha256:source")
                        .setSourceVersion(versionId.toString())
                        .setSourceUri("file:///tmp/a.md")
                        .setFilename("a.md"))
                .addElements(DocumentElement.newBuilder()
                        .setId("e1")
                        .setType(DocumentElementType.ELEMENT_PARAGRAPH)
                        .setText("hello")
                        .addSourceLocations(SourceLocation.newBuilder().setLocationId("loc-1"))
                        .addSourceLocations(SourceLocation.newBuilder().setLocationId("loc-2")))
                .addElements(DocumentElement.newBuilder()
                        .setId("e2")
                        .setType(DocumentElementType.ELEMENT_PARAGRAPH)
                        .setText("world")
                        .setCanonicalTextRange(CanonicalTextRange.newBuilder().setStart(0).setEnd(5)))
                .setProvenance(ExtractionProvenance.newBuilder()
                        .setPrimaryExecutionId("exec-1")
                        .addExecutions(ExtractionExecution.newBuilder()
                                .setExecutionId("exec-1")
                                .setExtractorId("markdown-adapter")
                                .setExtractorVersion("1.2.0")
                                .setEngineId("markdown-engine")
                                .setEngineVersion("9.1")
                                .setStageId("normalize")
                                .setPolicyId("markdown-policy")
                                .setPolicyVersion("4")
                                .setOutcome(outcome)
                                .addParents(ExecutionLink.newBuilder()
                                        .setExecutionId("exec-0")
                                        .setKind(ExecutionLinkKind.EXECUTION_LINK_FALLBACK))
                                .putAttributes("worker", "w1")
                                .setQuality(quality)))
                .addProjections(DocumentProjection.newBuilder()
                        .setProjectionId("proj-1")
                        .setFormat("markdown")
                        .setContractId("markdown-projection")
                        .setContractVersion("2.0")
                        .setOutcome(ProjectionOutcome.PROJECTION_OUTCOME_UNSUPPORTED)
                        .setUnsupportedReason("tables flattened")
                        .setDigest("sha256:proj"))
                .build();
    }

    private static Map<String, String> descriptor() {
        return Map.of(
                "schema_id", "synanton.extraction.document",
                "schema_version", "1.0",
                "processor_id", "markdown-adapter",
                "processor_version", "1.2.0",
                "format", "SERIALIZATION_PROTOBUF",
                "schema_digest", "sha256:schema",
                "payload_digest", "sha256:payload");
    }

    private static String inputJson(IngestionCacheClient cache) {
        ArgumentCaptor<KnowledgeProcessingRunRow> runs = ArgumentCaptor.forClass(KnowledgeProcessingRunRow.class);
        verify(cache, org.mockito.Mockito.times(3)).upsertKnowledgeProcessingRun(runs.capture());
        return runs.getAllValues().getFirst().inputJson();
    }

    @Test
    void persistsChunksWithProvenanceAndCompletesProcessingRun() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        PersistStage stage = new PersistStage(cache);

        UUID refId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID versionId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        StageContext context = new StageContext("demo", "job", props()).withSourceVersionId(versionId);

        stage.apply(doc(refId), context);

        ArgumentCaptor<List<ChunkRow>> chunks = ArgumentCaptor.forClass(List.class);
        verify(cache).recordKnowledgeChunks(eq("demo"), eq(refId), eq(versionId), any(), chunks.capture());
        assertThat(chunks.getValue()).singleElement().satisfies(row -> {
            assertThat(row.sourceVersionIds()).containsExactly(versionId);
            assertThat(row.processingRunId()).isNotNull();
            assertThat(row.sourceElementsJson()).contains("e1");
        });

        ArgumentCaptor<KnowledgeProcessingRunRow> runs = ArgumentCaptor.forClass(KnowledgeProcessingRunRow.class);
        verify(cache, org.mockito.Mockito.times(3)).upsertKnowledgeProcessingRun(runs.capture());
        UUID runId = runs.getAllValues().getFirst().processingRunId();
        assertThat(runs.getAllValues().stream().map(KnowledgeProcessingRunRow::status).toList())
            .containsExactly("RUNNING", "PUBLISHING", "COMPLETED");
        assertThat(runs.getAllValues().getLast().processingRunId()).isEqualTo(runId);
        assertThat(runs.getAllValues().getFirst().producer()).isEqualTo("synflux-knowledge");
        assertThat(runs.getAllValues().getFirst().producerVersion()).isEqualTo("semantic-v1:1");
        assertThat(runs.getAllValues().getFirst().inputJson()).contains(refId.toString());
        assertThat(runs.getAllValues().getFirst().inputSourceVersionIds()).containsExactly(versionId);
        assertThat(runs.getAllValues().getLast().outputJson()).contains("chunk_count");

        ArgumentCaptor<ManifestRow> manifest = ArgumentCaptor.forClass(ManifestRow.class);
        verify(cache).upsertManifest(manifest.capture());
        assertThat(manifest.getValue().processingRunId()).isEqualTo(runId);
    }

    @Test
    void persistsCanonicalPayloadEvidenceWithoutLegacyStructure() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        PersistStage stage = new PersistStage(cache);
        UUID refId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID versionId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        StageContext context = new StageContext("demo", "job", props()).withSourceVersionId(versionId);
        ParsedDocument parsed = parsed(refId,
            canonicalPayload(versionId, ExecutionOutcome.EXECUTION_OUTCOME_SUCCEEDED,
                QualityReport.newBuilder()
                    .setStatus(QualityStatus.QUALITY_STATUS_COMPLETE)
                    .setCompleteness(0.0)
                    .setConfidence(0.0)
                    .build()), descriptor());

        stage.apply(doc(refId, parsed), context);

        assertThat(inputJson(cache))
            .contains("\"extraction_payload_status\":\"PRESENT\"")
            .contains("\"extraction_schema_id\":\"synanton.extraction.document\"")
            .contains("\"extraction_schema_version\":\"1.0\"")
            .contains("\"extraction_processor_id\":\"markdown-adapter\"")
            .contains("\"extraction_processor_version\":\"1.2.0\"")
            .contains("\"extraction_payload_format\":\"SERIALIZATION_PROTOBUF\"")
            .contains("\"extraction_schema_digest\":\"sha256:schema\"")
            .contains("\"extraction_payload_digest\":\"sha256:payload\"")
            .contains("\"extraction_document_id\":\"doc-1\"")
            .contains("\"extraction_document_class\":\"DOCUMENT_CLASS_TEXT\"")
            .contains("\"extraction_document_version\":\"dv-1\"")
            .contains("\"extraction_source_media_type\":\"text/markdown\"")
            .contains("\"extraction_source_format\":\"markdown\"")
            .contains("\"extraction_source_digest\":\"sha256:source\"")
            .contains("\"extraction_source_version\":\"" + versionId + "\"")
            .contains("\"extraction_source_filename\":\"a.md\"")
            .contains("\"extraction_element_count\":2")
            .contains("\"extraction_source_location_count\":2")
            .contains("\"extraction_canonical_range_count\":1")
            .contains("\"extraction_projection_count\":1")
            .contains("\"extraction_projection_formats\":[\"markdown\"]")
            .contains("\"projection_id\":\"proj-1\"")
            .contains("\"contract_id\":\"markdown-projection\"")
            .contains("\"contract_version\":\"2.0\"")
            .contains("\"outcome\":\"PROJECTION_OUTCOME_UNSUPPORTED\"")
            .contains("\"unsupported_reason\":\"tables flattened\"")
            .contains("\"digest\":\"sha256:proj\"")
            .contains("\"content_available\":false")
            .contains("\"extraction_execution_count\":1")
            .contains("\"extraction_primary_execution_id\":\"exec-1\"")
            .contains("\"engine_id\":\"markdown-engine\"")
            .contains("\"engine_version\":\"9.1\"")
            .contains("\"stage_id\":\"normalize\"")
            .contains("\"policy_id\":\"markdown-policy\"")
            .contains("\"policy_version\":\"4\"")
            .contains("\"execution_id\":\"exec-0\"")
            .contains("\"kind\":\"EXECUTION_LINK_FALLBACK\"")
            .contains("\"worker\":\"w1\"")
            .contains("\"outcome\":\"EXECUTION_OUTCOME_SUCCEEDED\"")
            .contains("\"quality_status\":\"QUALITY_STATUS_COMPLETE\"")
            .contains("\"quality_completeness\":0.0")
            .contains("\"quality_confidence\":0.0")
            .doesNotContain("structural_");
    }

    @Test
    void persistsAbsentPayloadExplicitlyWithoutReconstructingStructure() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        PersistStage stage = new PersistStage(cache);
        UUID refId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID versionId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        stage.apply(doc(refId),
            new StageContext("demo", "job", props()).withSourceVersionId(versionId));

        assertThat(inputJson(cache))
            .contains("\"extraction_payload_status\":\"ABSENT\"")
            .contains("\"extraction_text_sha256\"")
            .doesNotContain("extraction_element_count")
            .doesNotContain("extraction_source_digest")
            .doesNotContain("structural_");
    }

    @Test
    void preservesPartialExecutionOutcomeAndQualitySeparately() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        PersistStage stage = new PersistStage(cache);
        UUID refId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID versionId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        ParsedDocument parsed = parsed(refId,
            canonicalPayload(versionId, ExecutionOutcome.EXECUTION_OUTCOME_PARTIAL,
                QualityReport.newBuilder()
                    .setStatus(QualityStatus.QUALITY_STATUS_PARTIAL)
                    .setCompleteness(0.0)
                    .addWarnings("page 2 skipped")
                    .build()), descriptor());

        stage.apply(doc(refId, parsed),
            new StageContext("demo", "job", props()).withSourceVersionId(versionId));

        assertThat(inputJson(cache))
            .contains("\"extraction_payload_status\":\"PRESENT\"")
            .contains("\"outcome\":\"EXECUTION_OUTCOME_PARTIAL\"")
            .contains("\"quality_status\":\"QUALITY_STATUS_PARTIAL\"")
            .contains("\"quality_completeness\":0.0")
            .contains("\"quality_warnings\":[\"page 2 skipped\"]")
            .doesNotContain("quality_confidence");
    }

    @Test
    void reprocessingSameInputsIsIdempotent() {
        IngestionCacheClient first = mock(IngestionCacheClient.class);
        IngestionCacheClient second = mock(IngestionCacheClient.class);
        UUID refId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID versionId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        new PersistStage(first).apply(doc(refId), new StageContext("demo", "job", props()).withSourceVersionId(versionId));
        new PersistStage(second).apply(doc(refId), new StageContext("demo", "job", props()).withSourceVersionId(versionId));

        UUID firstRunId = capturedRunId(first);
        UUID secondRunId = capturedRunId(second);
        assertThat(secondRunId).isEqualTo(firstRunId);
    }

    @Test
    void missingSourceVersionIdFailsClosed() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        PersistStage stage = new PersistStage(cache);

        assertThatThrownBy(() -> stage.apply(doc(UUID.randomUUID()), new StageContext("demo", "job", props())))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("source version");
        verify(cache, never()).insertChunks(anyList());
        verify(cache, never()).upsertManifest(any());
    }

    @Test
    void cacheWriteFailureRecordsFailedRunAndDoesNotPublishManifest() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        PersistStage stage = new PersistStage(cache);
        UUID refId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new IllegalStateException("cassandra down"))
            .when(cache).recordKnowledgeChunks(any(), any(), any(), any(), anyList());

        assertThatThrownBy(() -> stage.apply(doc(refId),
            new StageContext("demo", "job", props()).withSourceVersionId(versionId)))
            .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<KnowledgeProcessingRunRow> runs = ArgumentCaptor.forClass(KnowledgeProcessingRunRow.class);
        verify(cache, org.mockito.Mockito.times(2)).upsertKnowledgeProcessingRun(runs.capture());
        assertThat(runs.getAllValues().stream().map(KnowledgeProcessingRunRow::status).toList())
            .containsExactly("RUNNING", "FAILED");
        assertThat(runs.getAllValues().getLast().errorCode()).isEqualTo("IllegalStateException");
        assertThat(runs.getAllValues().getLast().errorMessage()).contains("cassandra down");
        verify(cache, never()).upsertManifest(any());
    }

    @Test
    void crashAfterManifestWriteBeforeCompletedLeavesRunFailedNotSuccessful() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        PersistStage stage = new PersistStage(cache);
        UUID refId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        // Failure injected after the manifest is durable, before the COMPLETED
        // transition — the manifest must not read as successful knowledge.
        org.mockito.Mockito.doAnswer(invocation -> {
            KnowledgeProcessingRunRow run = invocation.getArgument(0);
            if ("COMPLETED".equals(run.status())) {
                throw new IllegalStateException("crash before COMPLETED");
            }
            return null;
        }).when(cache).upsertKnowledgeProcessingRun(any());

        assertThatThrownBy(() -> stage.apply(doc(refId),
            new StageContext("demo", "job", props()).withSourceVersionId(versionId)))
            .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<KnowledgeProcessingRunRow> runs = ArgumentCaptor.forClass(KnowledgeProcessingRunRow.class);
        verify(cache, org.mockito.Mockito.times(4)).upsertKnowledgeProcessingRun(runs.capture());
        // The COMPLETED transition was attempted and crashed; the FAILED record wins.
        assertThat(runs.getAllValues().stream().map(KnowledgeProcessingRunRow::status).toList())
            .containsExactly("RUNNING", "PUBLISHING", "COMPLETED", "FAILED");
        verify(cache).upsertManifest(any());
    }

    @Test
    void chunkWriteAppendsOnlyThisRunsVersionAndRecordsJunction() {
        IngestionCacheClient cache = mock(IngestionCacheClient.class);
        PersistStage stage = new PersistStage(cache);
        UUID refId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID versionId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        StageContext context = new StageContext("demo", "job", props()).withSourceVersionId(versionId);

        stage.apply(doc(refId), context);

        ArgumentCaptor<List<ChunkRow>> chunks = ArgumentCaptor.forClass(List.class);
        verify(cache).recordKnowledgeChunks(
                eq("demo"), eq(refId), eq(versionId), any(), chunks.capture());
        for (ChunkRow row : chunks.getValue()) {
            // The row's set is the server-side append delta: this run's version only.
            assertThat(row.sourceVersionIds()).containsExactly(versionId);
        }
    }

    private static UUID capturedRunId(IngestionCacheClient cache) {
        ArgumentCaptor<KnowledgeProcessingRunRow> runs = ArgumentCaptor.forClass(KnowledgeProcessingRunRow.class);
        verify(cache, org.mockito.Mockito.times(3)).upsertKnowledgeProcessingRun(runs.capture());
        return runs.getAllValues().getFirst().processingRunId();
    }
}
