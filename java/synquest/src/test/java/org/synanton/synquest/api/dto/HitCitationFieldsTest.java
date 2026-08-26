package org.synanton.synquest.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HitCitationFieldsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldSerializeCitationAndUsageFields() throws Exception {
        Hit hit = new Hit(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                0,
                1.0,
                0.5,
                0.5,
                1,
                1,
                "snippet",
                "s3://bucket/key",
                1,
                2,
                "Chapter > Section",
                "Section",
                List.of("p1-e1", "p1-e2"),
                128,
                null,
                false,
                "{\"wall_ms\":42,\"stages\":[{\"name\":\"extract\"}]}");

        String json = MAPPER.writeValueAsString(hit);

        assertThat(json).contains("\"section_path\":\"Chapter > Section\"");
        assertThat(json).contains("\"source_elements\":[\"p1-e1\",\"p1-e2\"]");
        assertThat(json).contains("\"token_count\":128");
        assertThat(json).contains("\"ingest_usage\"");
    }
}
