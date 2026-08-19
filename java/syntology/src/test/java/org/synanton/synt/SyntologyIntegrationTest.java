package org.synanton.synt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.synanton.synt.app.SyntologyApplication;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
                .andExpect(jsonPath("$.module_id").value("syntology"))
                .andExpect(jsonPath("$.features.SHACL_VALIDATION").value("NATIVE"));
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

    @Test
    void shouldLoadHclSchemaBundleAndExposeIr() throws Exception {
        MockMultipartFile zip = new MockMultipartFile(
                "file",
                "ontology.zip",
                "application/zip",
                zipSchemaBundle()
        );

        mockMvc.perform(multipart("/api/v1/admin/ontology/schemas")
                        .file(zip)
                        .param("version", "1.1.0")
                        .param("entry", "schema.hcl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.1.0"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/admin/ontology/schemas/1.1.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ontology.id").value("supply-chain"))
                .andExpect(jsonPath("$.classes", hasSize(greaterThan(1))));

        mockMvc.perform(get("/api/v1/ontology/entities")
                        .param("label", "Supplier")
                        .param("version", "1.1.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Supplier"));
    }

    private static byte[] zipSchemaBundle() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            addZipEntry(zip, "schema.hcl", "schemas/schema.hcl");
            addZipEntry(zip, "_common.hcl", "schemas/_common.hcl");
        }
        return buffer.toByteArray();
    }

    private static void addZipEntry(ZipOutputStream zip, String name, String resource) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        try (var in = SyntologyIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            in.transferTo(zip);
        }
        zip.closeEntry();
    }
}
