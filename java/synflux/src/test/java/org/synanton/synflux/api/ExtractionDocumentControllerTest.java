package org.synanton.synflux.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.synanton.common.tenant.TenantContext;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ExtractionResultRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.ingestioncache.quarantine.QuarantineGeneration;
import org.synanton.ingestioncache.quarantine.QuarantineRecord;
import org.synanton.ingestioncache.quarantine.QuarantineStore;

class ExtractionDocumentControllerTest {

    private static final String TENANT = "tenant-a";
    private static final String CONNECTOR = "upload";
    private static final String SOURCE = "source-a";
    private static final String RESOURCE = "doc.txt";
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID CONTENT = UUID.randomUUID();
    private static final UUID GENERATION = UUID.randomUUID();

    private IngestionCacheClient cache;
    private QuarantineStore quarantine;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        cache = mock(IngestionCacheClient.class);
        quarantine = mock(QuarantineStore.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new ExtractionDocumentController(cache, quarantine)).build();
        TenantContext.setAnonymous(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void returnsResultsQuarantineAndProvenance() throws Exception {
        Instant now = Instant.parse("2026-11-24T00:00:00Z");
        when(cache.readSourceVersion(TENANT, CONNECTOR, SOURCE, RESOURCE, VERSION))
                .thenReturn(Optional.of(new SourceVersionRow(TENANT, CONNECTOR, SOURCE, RESOURCE,
                        VERSION, "digest", "rev", now, CONTENT, UUID.randomUUID(), "ref")));
        when(quarantine.currentGeneration(TENANT, VERSION)).thenReturn(Optional.of(GENERATION));
        when(quarantine.generations(TENANT, VERSION)).thenReturn(List.of(
                QuarantineGeneration.staged(TENANT, VERSION, GENERATION, "doc",
                        "extraction-v2", "a".repeat(64), "b".repeat(64), "model", "rev", now,
                        UUID.randomUUID(), "release-1", "c".repeat(64))));
        when(quarantine.records(TENANT, VERSION)).thenReturn(List.of(
                QuarantineRecord.open(TENANT, VERSION, GENERATION, 0, 1, "SCHEMA",
                        "STRUCTURAL", "bad line", "d".repeat(64), "{bad}", now)));
        when(cache.listExtractionResults(TENANT, CONTENT)).thenReturn(List.of(
                new ExtractionResultRow(TENANT, CONTENT, 0, VERSION,
                        "[{\"surfaceForm\":\"Acme\"}]", "[]", now, "extraction-v2",
                        "a".repeat(64), null, null, null, GENERATION)));

        mvc.perform(get("/v1/extraction/documents")
                        .param("connectorId", CONNECTOR)
                        .param("sourceId", SOURCE)
                        .param("resourceId", RESOURCE)
                        .param("versionId", VERSION.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentGeneration").value(GENERATION.toString()))
                .andExpect(jsonPath("$.generations[0].ontologyReleaseId").value("release-1"))
                .andExpect(jsonPath("$.results[0].entities[0].surfaceForm").value("Acme"))
                .andExpect(jsonPath("$.results[0].extractionGeneration").value(GENERATION.toString()))
                .andExpect(jsonPath("$.quarantine[0].reasonCode").value("SCHEMA"));
    }

    @Test
    void promotesKnownGenerationExplicitly() throws Exception {
        when(cache.readSourceVersion(TENANT, CONNECTOR, SOURCE, RESOURCE, VERSION))
                .thenReturn(Optional.of(new SourceVersionRow(TENANT, CONNECTOR, SOURCE, RESOURCE,
                        VERSION, "digest", "rev", Instant.now(), CONTENT, UUID.randomUUID(), "ref")));
        when(quarantine.generations(TENANT, VERSION)).thenReturn(List.of(
                QuarantineGeneration.staged(TENANT, VERSION, GENERATION, "doc", "extraction-v2",
                        "a".repeat(64), null, "model", "rev", Instant.now(), UUID.randomUUID())));
        when(quarantine.promote(eq(TENANT), eq(VERSION), eq(GENERATION), any(), any()))
                .thenReturn(new QuarantineStore.PromotionResult(GENERATION, true));

        mvc.perform(post("/v1/extraction/documents/promote")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connectorId\":\"upload\",\"sourceId\":\"source-a\","
                                + "\"resourceId\":\"doc.txt\",\"versionId\":\"" + VERSION + "\","
                                + "\"generationId\":\"" + GENERATION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentGeneration").value(GENERATION.toString()))
                .andExpect(jsonPath("$.applied").value(true));
    }
}
