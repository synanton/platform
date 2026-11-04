package org.synanton.synquest.ontology;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.synanton.common.jwt.JwtVerifier;
import org.synanton.common.tenant.TenantContextFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OntologyControllerTest {

    private static final String SECRET = "synquest-test-jwt-secret-at-least-32-bytes";
    private static final String SHA = "00c7e8fb6e1385ffe97710c26523a720dbe57cd922653bf1d099e7dec051a389";
    private static OntologyMapper mapper;
    private MockMvc mvc;

    @BeforeAll
    static void loadMapper() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("demo-data/eval/retrieval-benchmark/ontology-v2.jsonl"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("ontology-v2.jsonl not found from test working directory");
        }
        mapper = OntologyMapper.loadFrom(root.resolve("demo-data/eval/retrieval-benchmark/ontology-v2.jsonl"), SHA);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OntologyController(mapper))
                .addFilters(new TenantContextFilter(new JwtVerifier(SECRET), ""))
                .build();
    }

    @Test
    void noAuth_401() throws Exception {
        mvc.perform(get("/v1/demo/ontology/all")).andExpect(status().isUnauthorized());
    }

    @Test
    void all_273() throws Exception {
        mvc.perform(get("/v1/demo/ontology/all?df_band=all")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(273));
    }

    @Test
    void graphUseful_31() throws Exception {
        mvc.perform(get("/v1/demo/ontology/all?df_band=graph_useful")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(31));
    }

    @Test
    void graphUsefulHard_31() throws Exception {
        mvc.perform(get("/v1/demo/ontology/all?df_band=graph_useful&entity_class=hard")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(31));
    }

    @Test
    void graphUsefulSoftGlossary_0() throws Exception {
        mvc.perform(get("/v1/demo/ontology/all?df_band=graph_useful&entity_class=soft_glossary")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void pair_38() throws Exception {
        mvc.perform(get("/v1/demo/ontology/all?df_band=pair")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(38));
    }

    @Test
    void pairHard_30() throws Exception {
        mvc.perform(get("/v1/demo/ontology/all?df_band=pair&entity_class=hard")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(30));
    }

    @Test
    void pairSoftGlossary_8() throws Exception {
        mvc.perform(get("/v1/demo/ontology/all?df_band=pair&entity_class=soft_glossary")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(8));
    }

    @Test
    void unknownType_400() throws Exception {
        mvc.perform(get("/v1/demo/ontology/foobar")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unknown type"));
    }

    @Test
    void unknownBand_400() throws Exception {
        mvc.perform(get("/v1/demo/ontology/all?df_band=wrong")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unknown df_band"));
    }

    @Test
    void knownEntity_200() throws Exception {
        String entityId = mapper.filter("all", "all", null).getFirst().entityId();
        mvc.perform(get("/v1/demo/ontology/entity/{id}", entityId)
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity_id").value(entityId));
    }

    @Test
    void unknownEntity_404() throws Exception {
        mvc.perform(get("/v1/demo/ontology/entity/missing")
                        .header("Authorization", "Bearer " + token()))
                .andExpect(status().isNotFound());
    }

    private static String token() throws Exception {
        var claims = new JWTClaimsSet.Builder().subject("alice").claim("tenant_id", "demo-v2")
                .expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
