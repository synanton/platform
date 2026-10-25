package org.synanton.synquest.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the additive public response shape (#338). Existing fixtures are not
 * rewritten; a wire-shape change adds a new fixture/version.
 */
class PublicResponseFixtureTest {

    private static final UUID CONTENT_REF = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VERSION = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RUN = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void searchHitFixtureMatchesPublicShape() throws Exception {
        var hit = new KnowledgeSearchController.SearchHitResource(
                CONTENT_REF + ":0", 0.42,
                "APPENDIX A: DATA SOURCE DESCRIPTIONS",
                "The supply-chain policy governs logistics.",
                "/v1/knowledge/" + CONTENT_REF + ":0/provenance",
                List.of(VERSION), 2, 2,
                "APPENDIX A: DATA SOURCE DESCRIPTIONS",
                List.of("APPENDIX A: DATA SOURCE DESCRIPTIONS"));
        JsonNode actual = mapper.valueToTree(hit);
        assertThat(actual).isEqualTo(fixture("search-hit-v1.json"));
    }

    @Test
    void provenanceFixtureMatchesPublicShape() throws Exception {
        var identity = new KnowledgeProvenanceController.LiveSourceIdentity(
                VERSION, "manual-upload", "source-1", "doc.pdf",
                "manual-upload://source-1/doc.pdf");
        var provenance = new KnowledgeProvenanceController.KnowledgeProvenanceResource(
                CONTENT_REF + ":0", CONTENT_REF, List.of(VERSION), List.of(identity),
                "content-cache://tenant-a/" + CONTENT_REF,
                "manual-upload://source-1/doc.pdf",
                "a".repeat(64), "b".repeat(64), RUN,
                2, 2, "APPENDIX A: DATA SOURCE DESCRIPTIONS",
                List.of("APPENDIX A: DATA SOURCE DESCRIPTIONS"));
        JsonNode actual = mapper.valueToTree(provenance);
        assertThat(actual).isEqualTo(fixture("provenance-v1.json"));
    }

    @Test
    void fixtureHashesArePinned() throws Exception {
        assertThat(sha256("search-hit-v1.json"))
                .isEqualTo("34849229b9f1b830f34eb77e256e1c02a8de21839343811fe699d947c7928955");
        assertThat(sha256("provenance-v1.json"))
                .isEqualTo("b151f479463faae27047c35889a7c75f9a8404a8074d1266b7e3d15f1be501c1");
    }

    private JsonNode fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return mapper.readTree(in);
        }
    }

    private String sha256(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
            return HexFormat.of().formatHex(digest);
        }
    }
}
