package org.synanton.syntology.api.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.synanton.common.tenant.TenantContext;
import org.synanton.syntology.domain.model.OntologyVersion;
import org.synanton.syntology.domain.service.OntologyService;

class ActiveOntologyVersionControllerTest {

    private final OntologyService service = mock(OntologyService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ActiveOntologyVersionController(service))
            .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                    new ObjectMapper().registerModule(new JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
            .build();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void returnsVersionAndImmutableReferenceForTheVerifiedTenant() throws Exception {
        TenantContext.setAnonymous("tenant-a");
        UUID versionId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(service.activeVersion("tenant-a")).thenReturn(Optional.of(new OntologyVersion(
                versionId, "tenant-a", "v1.2.0", "Version 1.2", null, "/g", "ACTIVE",
                Instant.parse("2026-11-11T00:00:00Z"))));

        mvc.perform(get("/v1/tenants/tenant-a/active-ontology-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.activeVersion").value("v1.2.0"))
                .andExpect(jsonPath("$.versionRef").value("uuid:" + versionId))
                .andExpect(jsonPath("$.activatedAt").value("2026-11-11T00:00:00Z"));
    }

    @Test
    void rejectsTenantPathMismatch() throws Exception {
        TenantContext.setAnonymous("tenant-a");

        mvc.perform(get("/v1/tenants/tenant-b/active-ontology-version"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("tenant_scope_mismatch"));
    }

    @Test
    void returns404WhenNoActiveOntology() throws Exception {
        TenantContext.setAnonymous("tenant-a");
        when(service.activeVersion("tenant-a")).thenReturn(Optional.empty());

        mvc.perform(get("/v1/tenants/tenant-a/active-ontology-version"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("no_active_ontology"));
    }

    @Test
    void returns401WithoutVerifiedTenantContext() throws Exception {
        mvc.perform(get("/v1/tenants/tenant-a/active-ontology-version"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void returns503ForInfrastructureFailure() throws Exception {
        TenantContext.setAnonymous("tenant-a");
        when(service.activeVersion("tenant-a")).thenThrow(new IllegalStateException("db down"));

        mvc.perform(get("/v1/tenants/tenant-a/active-ontology-version"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("syntology_unavailable"));
    }
}
