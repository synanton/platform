package org.synanton.synquest.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.DocumentId;
import org.synanton.storage.contract.EmbeddingModelRef;
import org.synanton.storage.contract.GenerationId;
import org.synanton.storage.contract.PolicyContext;
import org.synanton.storage.contract.PrincipalRef;
import org.synanton.storage.contract.TenantScope;

/**
 * SYN-VECTOR-001 B1.1 acceptance: JSON round-trip for the vector DTOs.
 * Whole-object comparison only; proves field stability of the port shapes.
 */
class VectorDtoRoundTripTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jdk8.Jdk8Module())
                    // PrincipalRef carries a derived isService() getter with no creator
                    // property; storage-contract's shape, not this DTO's — tolerate it here.
                    .configure(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_UNKNOWN_PROPERTIES,
                            false);

    @Test
    void shouldRoundTripVectorSearchRequest() throws Exception {
        VectorSearchRequest request =
                new VectorSearchRequest(
                        new float[] {0.1f, 0.2f, 0.3f},
                        Optional.of(new EmbeddingModelRef("bge-base", "v1", "abc123")),
                        new EligibilityConstraints(
                                new TenantScope("demo"),
                                List.of(new PrincipalRef("user", "u1")),
                                new PolicyContext("p", "1"),
                                true),
                        10);

        VectorSearchRequest parsed =
                MAPPER.readValue(MAPPER.writeValueAsString(request), VectorSearchRequest.class);

        assertThat(parsed).isEqualTo(request);
    }

    @Test
    void shouldRoundTripVectorSearchResult() throws Exception {
        VectorSearchResult result =
                new VectorSearchResult(
                        List.of(
                                new SearchHit(
                                        ChunkId.of("c1"),
                                        new DocumentId("d1"),
                                        0.9,
                                        "text",
                                        Map.of("k", "v"))),
                        1);

        VectorSearchResult parsed =
                MAPPER.readValue(MAPPER.writeValueAsString(result), VectorSearchResult.class);

        assertThat(parsed).isEqualTo(result);
    }

    @Test
    void shouldRoundTripVectorProjection() throws Exception {
        VectorProjection projection =
                new VectorProjection(
                        ChunkId.of("c1"),
                        new DocumentId("d1"),
                        "demo",
                        new float[] {0.1f, 0.2f},
                        new EmbeddingModelRef("bge-base", "v1", "abc123"),
                        7L,
                        new GenerationId("g1"));

        VectorProjection parsed =
                MAPPER.readValue(MAPPER.writeValueAsString(projection), VectorProjection.class);

        assertThat(parsed).isEqualTo(projection);
    }
}
