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
import org.synanton.ingestioncache.domain.SourceRow;
import org.synanton.ingestioncache.domain.SourceVersionRow;
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
