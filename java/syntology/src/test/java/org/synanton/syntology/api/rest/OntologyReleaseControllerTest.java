package org.synanton.syntology.api.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.synanton.common.tenant.TenantContext;
import org.synanton.syntology.domain.model.OntologyVersion;
import org.synanton.syntology.domain.service.OntologyService;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OntologyReleaseControllerTest {

    private final OntologyService service = mock(OntologyService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new OntologyReleaseController(service))
        .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
            new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
        .build();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void returnsImmutableReleaseById() throws Exception {
        TenantContext.setAnonymous("tenant-a");
        UUID versionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(service.release("tenant-a", versionId.toString())).thenReturn(Optional.of(new OntologyVersion(
            versionId, "tenant-a", "it-books-v1", "IT Books v1", null, "/g", "DEPRECATED",
            Instant.parse("2026-11-25T09:08:54Z"), versionId.toString(), "a".repeat(64), "it-books")));

        mvc.perform(get("/v1/tenants/tenant-a/ontology-releases/" + versionId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ontologyId").value("it-books"))
            .andExpect(jsonPath("$.version").value("it-books-v1"))
            .andExpect(jsonPath("$.releaseId").value(versionId.toString()))
            .andExpect(jsonPath("$.releaseDigest").value("a".repeat(64)))
            .andExpect(jsonPath("$.status").value("DEPRECATED"));
    }

    @Test
    void rejectsTenantMismatchAndMissingRelease() throws Exception {
        TenantContext.setAnonymous("tenant-a");
        mvc.perform(get("/v1/tenants/tenant-b/ontology-releases/x"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("tenant_scope_mismatch"));

        when(service.release("tenant-a", "missing")).thenReturn(Optional.empty());
        mvc.perform(get("/v1/tenants/tenant-a/ontology-releases/missing"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("no_release"));
    }
}
