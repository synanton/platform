package org.synanton.synflux.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.SourceIdentityRow;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.ingestioncache.domain.SourceOutboxRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.synanton.ingestioncache.domain.WorkflowStateRow;
import org.synanton.synflux.lifecycle.FixtureSourceWorkflow;
import org.synanton.synflux.lifecycle.FilesystemFixturePolicy;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SourceControllerTenantTest {

    private static final String SECRET = "synflux-test-jwt-secret-at-least-32-bytes";

    private IngestionCacheClient cache;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        cache = mock(IngestionCacheClient.class);
        FixtureSourceWorkflow workflow = mock(FixtureSourceWorkflow.class);
        TenantContextFilter filter = new TenantContextFilter(new JwtVerifier(SECRET), "health-probe", "/actuator");
        mvc = MockMvcBuilders.standaloneSetup(new SourceController(cache,
                java.time.Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), java.time.ZoneOffset.UTC), workflow,
                new FilesystemFixturePolicy(java.nio.file.Path.of("/tmp"))))
            .addFilters(filter)
            .build();
    }

    @Test
    void sourceReadUsesVerifiedTenantEvenWhenQueryAndHeaderNameAnotherTenant() throws Exception {
        SourceRow source = new SourceRow("tenant-a", "shared-source", "fixture", "file",
            "file:///fixture", Set.of(), "ACTIVE", Instant.parse("2026-10-17T10:00:00Z"), null);
        when(cache.readSource("tenant-a", "shared-source")).thenReturn(Optional.of(source));

        mvc.perform(get("/v1/sources/shared-source")
                .header("Authorization", "Bearer " + token("tenant-a"))
                .header("X-Tenant", "tenant-b")
                .param("tenant", "tenant-b"))
            .andExpect(status().isOk());

        verify(cache).readSource("tenant-a", "shared-source");
        verify(cache, never()).readSource("tenant-b", "shared-source");
    }

    @Test
    void sourceCreateBindsTenantFromJwtAndIgnoresBodyTenant() throws Exception {
        when(cache.createSourceIfAbsent(any(SourceRow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post("/v1/sources")
                .header("Authorization", "Bearer " + token("tenant-a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenant_id\":\"tenant-b\",\"source_id\":\"fixture\","
                    + "\"connector_id\":\"fixture\",\"connector_type\":\"file\","
                    + "\"source_endpoint\":\"file:///tmp\",\"capability_flags\":[]}"))
            .andExpect(status().isCreated());

        ArgumentCaptor<SourceRow> source = ArgumentCaptor.forClass(SourceRow.class);
        verify(cache).createSourceIfAbsent(source.capture());
        assertThat(source.getValue().tenantId()).isEqualTo("tenant-a");
    }

    @Test
    void sourceEndpointCannotEscapeConfiguredFixtureRoot() throws Exception {
        mvc.perform(post("/v1/sources")
                .header("Authorization", "Bearer " + token("tenant-a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source_id\":\"outside\",\"connector_id\":\"fixture\","
                    + "\"connector_type\":\"file\",\"source_endpoint\":\"file:///etc\","
                    + "\"capability_flags\":[]}"))
            .andExpect(status().isBadRequest());

        verify(cache, never()).createSourceIfAbsent(org.mockito.ArgumentMatchers.any(SourceRow.class));
    }

    @Test
    void sourceVersionResponseUsesFrozenSnakeCaseFields() throws Exception {
        String reference = "v1:tenant_id=tenant-a&artifact_id=artifact-1&digest=sha256%3Aabc";
        SourceRow source = new SourceRow("tenant-a", "source-a", "fixture", "file",
            "file:///fixture", Set.of(), "ACTIVE", Instant.parse("2026-10-17T10:00:00Z"), null);
        SourceIdentityRow identity = new SourceIdentityRow("tenant-a", "fixture", "source-a", "manuals/a.md");
        SourceVersionRow version = new SourceVersionRow(
            "tenant-a", "fixture", "source-a", "manuals/a.md", UUID.randomUUID(), "sha256:abc", "etag:123",
            Instant.parse("2026-10-17T11:00:00Z"), UUID.randomUUID(), null, reference
        );
        when(cache.readSource("tenant-a", "source-a")).thenReturn(Optional.of(source));
        when(cache.listSourceIdentities("tenant-a", "source-a", "fixture")).thenReturn(List.of(identity));
        when(cache.listSourceVersions("tenant-a", "fixture", "source-a", "manuals/a.md"))
            .thenReturn(List.of(version));

        mvc.perform(get("/v1/sources/source-a/versions")
                .header("Authorization", "Bearer " + token("tenant-a")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].source_identity.tenant_id").value("tenant-a"))
            .andExpect(jsonPath("$[0].content_digest").value("sha256:abc"))
            .andExpect(jsonPath("$[0].content_cache_reference").value(reference))
            .andExpect(jsonPath("$[0].source_revision").value("etag:123"))
            .andExpect(jsonPath("$[0].version_id").value(version.versionId().toString()))
            .andExpect(jsonPath("$[0].contentDigest").doesNotExist())
            .andExpect(jsonPath("$[0].sourceIdentity").doesNotExist());
    }

    @Test
    void sourceStatusIncludesExtractionStatus() throws Exception {
        SourceRow source = new SourceRow("tenant-a", "source-a", "fixture", "file",
            "file:///fixture", Set.of(), "ACTIVE", Instant.parse("2026-10-17T10:00:00Z"), null);
        SourceIdentityRow identity = new SourceIdentityRow("tenant-a", "fixture", "source-a", "manuals/a.md");
        SourceVersionRow version = new SourceVersionRow(
            "tenant-a", "fixture", "source-a", "manuals/a.md", UUID.randomUUID(), "sha256:abc", "etag:123",
            Instant.parse("2026-10-17T11:00:00Z"), UUID.randomUUID(), null, "ref"
        );
        when(cache.readSource("tenant-a", "source-a")).thenReturn(Optional.of(source));
        when(cache.listWorkflowStates("tenant-a")).thenReturn(List.of());
        when(cache.listSourceIdentities("tenant-a", "source-a", "fixture")).thenReturn(List.of(identity));
        when(cache.listSourceVersions("tenant-a", "fixture", "source-a", "manuals/a.md"))
            .thenReturn(List.of(version));
        when(cache.readExtractionStatus("tenant-a", "fixture", "source-a", "manuals/a.md", version.versionId()))
            .thenReturn(Optional.of("KNOWLEDGE_READY"));
        when(cache.readChunks("tenant-a", version.contentRefId())).thenReturn(List.of());
        when(cache.readManifest("tenant-a", version.contentRefId())).thenReturn(Optional.empty());
        when(cache.listSourceOutbox("tenant-a", "fixture", "source-a", "manuals/a.md")).thenReturn(List.of());

        mvc.perform(get("/v1/sources/source-a/status")
                .header("Authorization", "Bearer " + token("tenant-a")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.extraction_status").value("KNOWLEDGE_READY"));
    }

    @Test
    void sourceReadDoesNotTrustTenantHeaderWithoutBearerToken() throws Exception {
        mvc.perform(get("/v1/sources/shared-source").header("X-Tenant", "tenant-a"))
            .andExpect(status().isUnauthorized());

        verify(cache, never()).readSource(any(), any());
    }

    @Test
    void sourceCreateAllowsUploadConnectorWithoutFilesystemRoot() throws Exception {
        when(cache.createSourceIfAbsent(any(SourceRow.class))).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post("/v1/sources")
                .header("Authorization", "Bearer " + token("tenant-a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source_id\":\"uploads\",\"connector_id\":\"filesystem\","
                    + "\"connector_type\":\"upload\",\"capability_flags\":[]}"))
            .andExpect(status().isCreated());

        ArgumentCaptor<SourceRow> source = ArgumentCaptor.forClass(SourceRow.class);
        verify(cache).createSourceIfAbsent(source.capture());
        assertThat(source.getValue().connectorType()).isEqualTo("upload");
        assertThat(source.getValue().sourceEndpoint()).isEmpty();
    }

    @Test
    void sourceStatusReportsCompletedAndIndexedAfterProjectionAck() throws Exception {
        StatusFixture fixture = statusFixture(true);

        mvc.perform(get("/v1/sources/source-a/status")
                .header("Authorization", "Bearer " + token("tenant-a")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source_id").value("source-a"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.indexed").value(true))
            .andExpect(jsonPath("$.chunk_count").value(1));
    }

    @Test
    void sourceStatusIsNotIndexedUntilTheOutboxEventIsPublished() throws Exception {
        StatusFixture fixture = statusFixture(false);

        mvc.perform(get("/v1/sources/source-a/status")
                .header("Authorization", "Bearer " + token("tenant-a")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.indexed").value(false))
            .andExpect(jsonPath("$.chunk_count").value(1));
    }

    private StatusFixture statusFixture(boolean published) {
        String tenant = "tenant-a";
        Instant now = Instant.parse("2026-10-17T12:00:00Z");
        UUID versionId = UUID.randomUUID();
        UUID contentRef = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        SourceRow source = new SourceRow(tenant, "source-a", "upload", "upload",
            "upload://source-a", Set.of(), "ACTIVE", now, null);
        SourceIdentityRow identity = new SourceIdentityRow(tenant, "upload", "source-a", "docs/a.txt");
        SourceVersionRow version = new SourceVersionRow(tenant, "upload", "source-a", "docs/a.txt",
            versionId, "sha256:abc", "rev-1", now, contentRef, UUID.randomUUID(), "");
        ManifestRow manifest = new ManifestRow(tenant, contentRef, now, 1, "semantic-v1", 1,
            "EMBEDDED", "CONTENT_CACHE", "", "upload://source-a/docs/a.txt", "sha256:abc", 10,
            "text/plain", "FULL", null, "", null, runId);
        SourceOutboxRow event = new SourceOutboxRow(tenant, "upload", "source-a", "docs/a.txt",
            UUID.randomUUID(), "SourcePublished", versionId, null, "{}", now, published);
        WorkflowStateRow workflow = new WorkflowStateRow(tenant, UUID.randomUUID(), "ingestion", 1,
            UUID.randomUUID(), runId, "source-a", "docs/a.txt", "user", "USER", "req-1", null,
            "COMPLETED", "INGEST", now, now, now, now, null, null, null, 0, null);
        ChunkRow chunk = new ChunkRow(tenant, contentRef, 0, "text", "sha", 1, 1, "", "", "");

        when(cache.readSource(tenant, "source-a")).thenReturn(Optional.of(source));
        when(cache.listWorkflowStates(tenant)).thenReturn(List.of(workflow));
        when(cache.listSourceIdentities(tenant, "source-a", "upload")).thenReturn(List.of(identity));
        when(cache.listSourceVersions(tenant, "upload", "source-a", "docs/a.txt")).thenReturn(List.of(version));
        when(cache.readChunks(tenant, contentRef)).thenReturn(List.of(chunk));
        when(cache.readManifest(tenant, contentRef)).thenReturn(Optional.of(manifest));
        when(cache.listSourceOutbox(tenant, "upload", "source-a", "docs/a.txt")).thenReturn(List.of(event));
        return new StatusFixture();
    }

    private record StatusFixture() {}

    private static String token(String tenantId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .subject("fixture-user")
            .claim("tenant_id", tenantId)
            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
