package org.synanton.synquest.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.synanton.ingestioncache.client.IngestionCacheClient;
import org.synanton.ingestioncache.domain.ChunkRow;
import org.synanton.ingestioncache.domain.ManifestRow;
import org.synanton.ingestioncache.domain.SourceIdentityRow;
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Finding 1 of the #296 review: a provenance_ref that does not resolve is the
 * dangling-reference class the source-model review eliminated — every search
 * hit carries provenance_ref, so the endpoint must resolve it to the source
 * version(s) and the Content Cache artifact while the producing run is
 * COMPLETED (manifest visibility), and 404 otherwise.
 */
class KnowledgeProvenanceControllerTest {

    private static final String SECRET = "synquest-test-jwt-secret-at-least-32-bytes";

    private IngestionCacheClient cacheClient;
    private MockMvc mvc;
    private UUID contentRefId;
    private UUID sourceVersionId;
    private UUID processingRunId;

    @BeforeEach
    void setUp() {
        cacheClient = mock(IngestionCacheClient.class);
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeProvenanceController(cacheClient))
                .addFilters(new TenantContextFilter(new JwtVerifier(SECRET), ""))
                .build();
        contentRefId = UUID.nameUUIDFromBytes("chunk:tenant-a".getBytes());
        sourceVersionId = UUID.nameUUIDFromBytes("version:tenant-a".getBytes());
        processingRunId = UUID.nameUUIDFromBytes("run:tenant-a".getBytes());
    }

    private static String token() throws Exception {
        return token("tenant-a");
    }

    private static String token(String tenantId) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .subject("alice")
                .claim("tenant_id", tenantId)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private void visibleManifest() {
        when(cacheClient.readManifest(eq("tenant-a"), eq(contentRefId))).thenReturn(Optional.of(new ManifestRow(
                "tenant-a", contentRefId, Instant.now(), 14, "structural", 1, "COMPLETED", "hot",
                "", "file:///doc.md", "aa".repeat(32), 12, "text/markdown", "n/a", "", "",
                "content-cache://tenant-a/" + contentRefId)));
    }

    private void chunkAt(int ordinal) {
        when(cacheClient.readChunks(eq("tenant-a"), eq(contentRefId))).thenReturn(List.of(new ChunkRow(
                "tenant-a", contentRefId, ordinal, "The supply-chain policy governs logistics.",
                "bb".repeat(32), 2, 2, "APPENDIX A: DATA SOURCE DESCRIPTIONS", "", "", "[]", 0, "", false,
                List.of("PUBLIC"), "", "", 0, Set.of(sourceVersionId), processingRunId)));
    }

    @Test
    void provenanceRefFromSearchHitResolvesToSourceVersionsAndContentCacheReference() throws Exception {
        visibleManifest();
        chunkAt(0);
        // The path exactly as a search hit emits it (frozen search-model provenance_ref).
        String provenanceRef = "/v1/knowledge/" + contentRefId + ":0/provenance";

        mvc.perform(get(provenanceRef).header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object_id").value(contentRefId + ":0"))
                .andExpect(jsonPath("$.content_ref_id").value(contentRefId.toString()))
                .andExpect(jsonPath("$.source_version_ids[0]").value(sourceVersionId.toString()))
                .andExpect(jsonPath("$.content_cache_reference").value(
                        "content-cache://tenant-a/" + contentRefId))
                .andExpect(jsonPath("$.source_uri").value("file:///doc.md"))
                .andExpect(jsonPath("$.source_sha256").value("aa".repeat(32)))
                .andExpect(jsonPath("$.chunk_sha256").value("bb".repeat(32)))
                .andExpect(jsonPath("$.processing_run_id").value(processingRunId.toString()))
                .andExpect(jsonPath("$.page_start").value(2))
                .andExpect(jsonPath("$.page_end").value(2))
                .andExpect(jsonPath("$.section").value("APPENDIX A: DATA SOURCE DESCRIPTIONS"))
                .andExpect(jsonPath("$.section_path[0]").value("APPENDIX A: DATA SOURCE DESCRIPTIONS"));
    }

    @Test
    void duplicateProvenanceExposesTheLiveSiblingAndHidesTheDeletedVersion() throws Exception {
        UUID deletedVersion = UUID.nameUUIDFromBytes("version:deleted".getBytes());
        UUID liveVersion = UUID.nameUUIDFromBytes("version:live".getBytes());
        visibleManifest();
        when(cacheClient.readChunks(eq("tenant-a"), eq(contentRefId))).thenReturn(List.of(new ChunkRow(
                "tenant-a", contentRefId, 0, "shared chunk", "bb".repeat(32), 2, 2,
                "APPENDIX A: DATA SOURCE DESCRIPTIONS", "SECTION", "APPENDIX A",
                "[]", 10, "", false, List.of("PUBLIC"), "", "", 0,
                Set.of(deletedVersion, liveVersion), processingRunId)));
        when(cacheClient.readTombstonedVersionIds("tenant-a")).thenReturn(Set.of(deletedVersion));
        SourceRow source = new SourceRow("tenant-a", "uploads", "manual-upload", "upload",
                "upload://uploads", Set.of(), "ACTIVE", Instant.now(), null);
        when(cacheClient.listSources("tenant-a")).thenReturn(List.of(source));
        when(cacheClient.listSourceIdentities("tenant-a", "uploads", "manual-upload")).thenReturn(List.of(
                new SourceIdentityRow("tenant-a", "manual-upload", "uploads", "a.pdf"),
                new SourceIdentityRow("tenant-a", "manual-upload", "uploads", "b.pdf")));
        when(cacheClient.listSourceVersions("tenant-a", "manual-upload", "uploads", "a.pdf")).thenReturn(List.of(
                version("tenant-a", "uploads", "a.pdf", deletedVersion)));
        when(cacheClient.listSourceVersions("tenant-a", "manual-upload", "uploads", "b.pdf")).thenReturn(List.of(
                version("tenant-a", "uploads", "b.pdf", liveVersion)));

        mvc.perform(get("/v1/knowledge/" + contentRefId + ":0/provenance")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source_version_ids[0]").value(liveVersion.toString()))
                .andExpect(jsonPath("$.source_version_ids.length()").value(1))
                .andExpect(jsonPath("$.source_identities[0].source_version_id").value(liveVersion.toString()))
                .andExpect(jsonPath("$.source_identities[0].resource_id").value("b.pdf"))
                .andExpect(jsonPath("$.source_identities[0].source_uri")
                        .value("manual-upload://uploads/b.pdf"))
                .andExpect(jsonPath("$.chunk_sha256").value("bb".repeat(32)));
    }

    @Test
    void crossTenantProvenanceReadsOnlyTheCallersTenant() throws Exception {
        when(cacheClient.readManifest(eq("tenant-b"), eq(contentRefId))).thenReturn(Optional.empty());

        mvc.perform(get("/v1/knowledge/" + contentRefId + ":0/provenance")
                        .header("Authorization", "Bearer " + token("tenant-b")))
                .andExpect(status().isNotFound());

        verify(cacheClient).readManifest("tenant-b", contentRefId);
        verify(cacheClient, never()).readManifest("tenant-a", contentRefId);
        verify(cacheClient, never()).readChunks("tenant-a", contentRefId);
    }

    @Test
    void provenanceForOnlyTombstonedVersionsYields404() throws Exception {
        visibleManifest();
        chunkAt(0);
        when(cacheClient.readTombstonedVersionIds("tenant-a")).thenReturn(Set.of(sourceVersionId));

        mvc.perform(get("/v1/knowledge/" + contentRefId + ":0/provenance")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound());
    }

    private static SourceVersionRow version(String tenant, String source, String resource, UUID versionId) {
        return new SourceVersionRow(tenant, "manual-upload", source, resource, versionId,
                "sha256:abc", "rev", Instant.now(), UUID.randomUUID(), null, "content-ref");
    }

    @Test
    void invisibleManifestYields404() throws Exception {
        when(cacheClient.readManifest(eq("tenant-a"), eq(contentRefId))).thenReturn(Optional.empty());

        mvc.perform(get("/v1/knowledge/" + contentRefId + ":0/provenance")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonPaginatedChunkExposesNullPageFields() throws Exception {
        visibleManifest();
        when(cacheClient.readChunks(eq("tenant-a"), eq(contentRefId))).thenReturn(List.of(new ChunkRow(
                "tenant-a", contentRefId, 0, "EPUB body", "bb".repeat(32), -1, -1, "", "",
                "", "[]", 0, "", false, List.of("PUBLIC"), "", "", 0,
                Set.of(sourceVersionId), processingRunId)));

        mvc.perform(get("/v1/knowledge/" + contentRefId + ":0/provenance")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page_start").doesNotExist())
                .andExpect(jsonPath("$.page_end").doesNotExist())
                .andExpect(jsonPath("$.section").doesNotExist())
                .andExpect(jsonPath("$.section_path").doesNotExist());
    }

    @Test
    void unknownChunkOrdinalYields404() throws Exception {
        visibleManifest();
        chunkAt(0);

        mvc.perform(get("/v1/knowledge/" + contentRefId + "#3/provenance")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedObjectIdYields400() throws Exception {
        visibleManifest();
        chunkAt(0);

        mvc.perform(get("/v1/knowledge/not-a-chunk-id/provenance")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/knowledge/" + contentRefId + ":-1/provenance")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isBadRequest());
    }
}
