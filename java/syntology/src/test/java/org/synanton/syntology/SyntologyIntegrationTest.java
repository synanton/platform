package org.synanton.syntology;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.synanton.syntology.app.SyntologyApplication;
import org.synanton.syntology.domain.port.out.MetadataRepository;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Autowired
    private MetadataRepository metadataRepository;

    @Test
    void shouldSeedOntologyAndReturnGraph() throws Exception {
        mockMvc.perform(get("/api/v1/ontology/versions").header("X-Tenant-ID", "demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].releaseId").isNotEmpty())
                .andExpect(jsonPath("$[0].releaseDigest").isNotEmpty());

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
    void shouldValidateRelationDomainAndRange() throws Exception {
        mockMvc.perform(
                        post("/api/v1/ontology/validate-relation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subjectType\":\"Product\",\"predicate\":\"has supplier\","
                                        + "\"objectType\":\"Supplier\",\"ontologyVersion\":\"1.0.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conformant").value(true))
                .andExpect(jsonPath("$.violations").isEmpty());
    }

    @Test
    void shouldRejectRelationDomainMismatchWithStructuredViolation() throws Exception {
        mockMvc.perform(
                        post("/api/v1/ontology/validate-relation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subjectType\":\"Order\",\"predicate\":\"has supplier\","
                                        + "\"objectType\":\"Supplier\",\"ontologyVersion\":\"1.0.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conformant").value(false))
                .andExpect(jsonPath("$.violations[0].code").value("domain_mismatch"));
    }

    @Test
    void shouldValidateTransitiveTypeInheritance() throws Exception {
        MockMultipartFile zip = new MockMultipartFile(
                "file", "ontology.zip", "application/zip", zipSchemaBundle());
        mockMvc.perform(multipart("/api/v1/admin/ontology/schemas")
                        .file(zip)
                        .param("version", "1.2.0")
                        .param("entry", "schema.hcl"))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/ontology/validate-relation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subjectType\":\"Purchase Order\",\"predicate\":\"belongs to\","
                                        + "\"objectType\":\"Sales Order\",\"ontologyVersion\":\"1.2.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conformant").value(true));
    }

    @Test
    void shouldRejectUnknownRelationPredicate() throws Exception {
        mockMvc.perform(
                        post("/api/v1/ontology/validate-relation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"subjectType\":\"Product\",\"predicate\":\"nope\","
                                        + "\"objectType\":\"Supplier\",\"ontologyVersion\":\"1.0.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conformant").value(false))
                .andExpect(jsonPath("$.violations[0].code").value("unknown_predicate"));
    }

    @Test
    void publishesActualItBooksV1SchemaWithStableReleaseIdentity() throws Exception {
        byte[] schema = actualItBooksSchema();
        MockMultipartFile zip = new MockMultipartFile(
                "file", "it-books-v1.zip", "application/zip", zipBytes(schema));

        mockMvc.perform(multipart("/api/v1/admin/ontology/schemas")
                        .file(zip)
                        .param("version", "it-books-v1")
                        .param("entry", "schema.hcl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ontologyId").value("it-books"))
                .andExpect(jsonPath("$.releaseId").isNotEmpty())
                .andExpect(jsonPath("$.releaseDigest").isNotEmpty());

        var stored = metadataRepository.findByVersion("demo", "it-books-v1").orElseThrow();
        mockMvc.perform(get("/v1/tenants/demo/active-ontology-version")
                        .param("ontologyId", "it-books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releaseId").value(stored.releaseId()))
                .andExpect(jsonPath("$.releaseDigest").value(stored.releaseDigest()));

        mockMvc.perform(get("/api/v1/admin/ontology/schemas/it-books-v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ontology.id").value("it-books"))
                .andExpect(jsonPath("$.classes", hasSize(greaterThan(1))));

        var reloaded = metadataRepository.findByVersion("demo", "it-books-v1").orElseThrow();
        assertThat(reloaded.releaseId()).isEqualTo(stored.releaseId());
        assertThat(reloaded.releaseDigest()).isEqualTo(stored.releaseDigest());

        mockMvc.perform(get("/v1/tenants/demo/active-ontology-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ontologyId").value("supply-chain"));
    }

    @Test
    void publishesItBooksReleaseWithoutReplacingSupplyChainActiveRelease() throws Exception {
        MockMultipartFile zip = new MockMultipartFile(
                "file", "it-books.zip", "application/zip", zipResource("schemas/it-books-test.hcl"));

        mockMvc.perform(multipart("/api/v1/admin/ontology/schemas")
                        .file(zip)
                        .param("version", "it-books-test-v1")
                        .param("entry", "schema.hcl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ontologyId").value("it-books"))
                .andExpect(jsonPath("$.releaseId").isNotEmpty())
                .andExpect(jsonPath("$.releaseDigest").isNotEmpty());

        mockMvc.perform(get("/v1/tenants/demo/active-ontology-version")
                        .param("ontologyId", "it-books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ontologyId").value("it-books"))
                .andExpect(jsonPath("$.activeVersion").value("it-books-test-v1"));

        // The default active lookup remains Supply Chain; IT Books activation is not global.
        mockMvc.perform(get("/v1/tenants/demo/active-ontology-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ontologyId").value("supply-chain"))
                .andExpect(jsonPath("$.activeVersion").value(org.hamcrest.Matchers.not("it-books-test-v1")));

        mockMvc.perform(get("/api/v1/admin/ontology/schemas/it-books-test-v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ontology.id").value("it-books"));

        var firstRelease = metadataRepository.findByVersion("demo", "it-books-test-v1").orElseThrow();
        MockMultipartFile secondZip = new MockMultipartFile(
                "file", "it-books-v2.zip", "application/zip", zipResource("schemas/it-books-test.hcl"));
        mockMvc.perform(multipart("/api/v1/admin/ontology/schemas")
                        .file(secondZip)
                        .param("version", "it-books-test-v2")
                        .param("entry", "schema.hcl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ontologyId").value("it-books"));

        var retained = metadataRepository.findByVersion("demo", "it-books-test-v1").orElseThrow();
        assertThat(retained.releaseId()).isEqualTo(firstRelease.releaseId());
        assertThat(retained.releaseDigest()).isEqualTo(firstRelease.releaseDigest());
        assertThat(retained.status()).isEqualTo("DEPRECATED");

        mockMvc.perform(get("/v1/tenants/demo/active-ontology-version")
                        .param("ontologyId", "it-books"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeVersion").value("it-books-test-v2"));
        mockMvc.perform(get("/v1/tenants/demo/active-ontology-version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ontologyId").value("supply-chain"));
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
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.releaseId").isNotEmpty())
                .andExpect(jsonPath("$.releaseDigest").isNotEmpty());

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

    private static byte[] zipResource(String resource) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            addZipEntry(zip, "schema.hcl", resource);
        }
        return buffer.toByteArray();
    }

    private static byte[] zipBytes(byte[] content) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("schema.hcl"));
            zip.write(content);
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    private static byte[] actualItBooksSchema() throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("schemas/it-books/v1/schema.hcl");
            if (java.nio.file.Files.exists(candidate)) {
                return java.nio.file.Files.readAllBytes(candidate);
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("schemas/it-books/v1/schema.hcl not found from "
                + Path.of("").toAbsolutePath());
    }

    private static void addZipEntry(ZipOutputStream zip, String name, String resource) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        try (var in = SyntologyIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            in.transferTo(zip);
        }
        zip.closeEntry();
    }
}
