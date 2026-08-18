package org.synanton.synt;

import org.synanton.synt.app.SyntologyApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = SyntologyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SyntologyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldSeedOntologyAndReturnGraph() throws Exception {
        mockMvc.perform(get("/api/v1/ontology/versions").header("X-Tenant-ID", "demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))));

        mockMvc.perform(get("/api/v1/ontology/graph").param("version", "1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.edges").exists());
    }

    @Test
    void shouldExposeCapabilities() throws Exception {
        mockMvc.perform(get("/api/v1/ontology/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.module_id").value("syntology"));
    }

    @Test
    void shouldListMcpTools() throws Exception {
        mockMvc.perform(get("/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("syntology"));
    }

    @Test
    void shouldResolveEntityByLabel() throws Exception {
        mockMvc.perform(get("/api/v1/ontology/entities")
                        .param("label", "Product")
                        .param("version", "1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Product"));
    }

    @Test
    void shouldValidateConceptStub() throws Exception {
        mockMvc.perform(
                        post("/api/v1/ontology/validate")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"label\":\"Product\",\"uri\":\"http://example#Product\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }
}
