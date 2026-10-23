package org.synanton.eventing.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventingJsonContractTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID COMMAND_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID CORRELATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CAUSATION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private final ObjectMapper mapper = EventingJson.newMapper();

    @Test
    void eventVersionOneMatchesTheCanonicalGoldenJson() throws IOException {
        Event<ContentPublished> event = event();

        assertThat(mapper.writeValueAsString(event)).isEqualTo(golden("event.v1.json"));
    }

    @Test
    void commandVersionOneMatchesTheCanonicalGoldenJson() throws IOException {
        Command<GenerateEmbedding> command = command();

        assertThat(mapper.writeValueAsString(command)).isEqualTo(golden("command.v1.json"));
    }

    @Test
    void eventRoundTripPreservesIdsTimeCorrelationTenantSecurityAndTypedPayload() throws IOException {
        Event<ContentPublished> original = event();
        byte[] serialized = mapper.writeValueAsBytes(original);

        Event<ContentPublished> restored = mapper.readValue(
                serialized, new TypeReference<>() {});

        assertThat(restored).isEqualTo(original);
        assertThat(restored.eventId()).isEqualTo(EVENT_ID);
        assertThat(restored.occurredAt()).isEqualTo(Instant.parse("2026-10-23T12:00:00Z"));
        assertThat(restored.tenantId()).isEqualTo("tenant-a");
        assertThat(restored.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(restored.causationId()).contains(CAUSATION_ID);
        assertThat(restored.security().classification()).isEqualTo("CONFIDENTIAL");
        assertThat(restored.payload()).isEqualTo(new ContentPublished("content-123", 7));
    }

    @Test
    void commandRoundTripPreservesVersionAndOptionalOptimisticConcurrency() throws IOException {
        Command<GenerateEmbedding> original = command();

        Command<GenerateEmbedding> restored = mapper.readValue(
                mapper.writeValueAsBytes(original), new TypeReference<>() {});

        assertThat(restored).isEqualTo(original);
        assertThat(restored.commandId()).isEqualTo(COMMAND_ID);
        assertThat(restored.commandVersion()).isEqualTo(1);
        assertThat(restored.expectedVersion()).contains(3L);
        assertThat(restored.issuedAt()).isEqualTo(Instant.parse("2026-10-23T12:00:00Z"));
    }

    @Test
    void eventDeserializerIgnoresUnknownAdditiveEnvelopeFields() throws IOException {
        ObjectNode json = (ObjectNode) mapper.valueToTree(event());
        json.put("future_optional_metadata", "added-by-a-newer-producer");

        Event<ContentPublished> restored = mapper.readValue(
                mapper.writeValueAsBytes(json), new TypeReference<>() {});

        assertThat(restored).isEqualTo(event());
    }

    @Test
    void eventDeserializerRejectsMissingOrMismatchedTenantRepresentations() throws IOException {
        ObjectNode canonical = (ObjectNode) mapper.valueToTree(event());
        ObjectNode missingFlatTenant = canonical.deepCopy();
        missingFlatTenant.remove("tenant_id");
        ObjectNode missingNestedTenant = canonical.deepCopy();
        missingNestedTenant.remove("tenant");
        ObjectNode mismatchedTenant = canonical.deepCopy();
        mismatchedTenant.withObject("tenant").put("id", "tenant-b");

        assertThatThrownBy(() -> readEvent(missingFlatTenant))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> readEvent(missingNestedTenant))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> readEvent(mismatchedTenant))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void uuidSerializationUsesLowercaseHyphenatedStrings() throws JsonProcessingException {
        assertThat(mapper.writeValueAsString(EVENT_ID))
                .isEqualTo("\"11111111-1111-4111-8111-111111111111\"");
    }

    private static Event<ContentPublished> event() {
        return new Event<>(
                EVENT_ID,
                "ContentPublished",
                1,
                Instant.parse("2026-10-23T12:00:00Z"),
                new Producer("content-cache", "1.26"),
                "tenant-a",
                Optional.of("document-42"),
                CORRELATION_ID,
                Optional.of(CAUSATION_ID),
                ReplayClass.REPLAY_SAFE,
                Optional.empty(),
                new SecurityMetadata(
                        ActorKind.SERVICE,
                        "content-cache",
                        "CONFIDENTIAL",
                        2,
                        List.of("customer-data", "retention-30d")),
                new ContentPublished("content-123", 7));
    }

    private static Command<GenerateEmbedding> command() {
        return new Command<>(
                COMMAND_ID,
                "GenerateEmbedding",
                1,
                Instant.parse("2026-10-23T12:00:00Z"),
                "tenant-a",
                new SecurityMetadata(
                        ActorKind.PRINCIPAL,
                        "principal-42",
                        "CONFIDENTIAL",
                        2,
                        List.of("customer-data")),
                new CommandTarget("document", "document-42"),
                CORRELATION_ID,
                Optional.empty(),
                Optional.of(3L),
                new GenerateEmbedding("document-42", "model-v4"));
    }

    private static String golden(String fileName) throws IOException {
        try (InputStream input = EventingJsonContractTest.class.getResourceAsStream("/golden/" + fileName)) {
            if (input == null) {
                throw new IOException("Missing golden fixture " + fileName);
            }
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
    }

    private Event<ContentPublished> readEvent(ObjectNode json) throws IOException {
        return mapper.readValue(mapper.writeValueAsBytes(json), new TypeReference<>() {});
    }

    record ContentPublished(
            @com.fasterxml.jackson.annotation.JsonProperty("content_ref_id") String contentRefId,
            @com.fasterxml.jackson.annotation.JsonProperty("generation") int generation) {
    }

    record GenerateEmbedding(
            @com.fasterxml.jackson.annotation.JsonProperty("document_id") String documentId,
            @com.fasterxml.jackson.annotation.JsonProperty("model_id") String modelId) {
    }
}
