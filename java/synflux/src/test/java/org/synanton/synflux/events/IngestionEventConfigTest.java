package org.synanton.synflux.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.synanton.eventing.contract.ActorKind;
import org.synanton.eventing.contract.Event;
import org.synanton.eventing.contract.EventTypeRegistry;
import org.synanton.eventing.contract.Producer;
import org.synanton.eventing.contract.ReplayClass;
import org.synanton.eventing.contract.SecurityMetadata;

class IngestionEventConfigTest {

    private final EventTypeRegistry registry = new IngestionEventConfig().ingestionEventTypes();

    @Test
    void registersSourcePublishedAndSourceDeletedAsReplaySafeV1() {
        assertThatCode(() -> registry.validate(event("SourcePublished", 1, ReplayClass.REPLAY_SAFE)))
                .doesNotThrowAnyException();
        assertThatCode(() -> registry.validate(event("SourceDeleted", 1, ReplayClass.REPLAY_SAFE)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownEventType() {
        assertThatThrownBy(() -> registry.validate(event("SourceUnknown", 1, ReplayClass.REPLAY_SAFE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownSchemaVersion() {
        assertThatThrownBy(() -> registry.validate(event("SourcePublished", 2, ReplayClass.REPLAY_SAFE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsReplayClassMismatch() {
        assertThatThrownBy(() -> registry.validate(event("SourcePublished", 1, ReplayClass.NOT_REPLAYABLE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourcePublishedSerializesWithTheExistingWireFieldNames() throws Exception {
        SourcePublished payload = new SourcePublished(
                new SourceIdentity("tenant-a", "upload", "src", "res"),
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                "sha256:abc",
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                Instant.parse("2026-11-06T12:00:00Z"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                "wf-1",
                null);
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        String json = mapper.writeValueAsString(payload);

        assertThat(json)
                .contains("\"source_identity\"")
                .contains("\"tenant_id\":\"tenant-a\"")
                .contains("\"connector_id\":\"upload\"")
                .contains("\"version_id\"")
                .contains("\"content_digest\":\"sha256:abc\"")
                .contains("\"content_ref_id\"")
                .contains("\"observed_at\":\"2026-11-06T12:00:00Z\"")
                .contains("\"run_id\"")
                .contains("\"operation_id\"")
                .contains("\"workflow_id\":\"wf-1\"");
        assertThat(json).doesNotContain("content_cache_reference");
    }

    private static Event<String> event(String eventType, int eventVersion, ReplayClass replayClass) {
        return new Event<>(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                eventType,
                eventVersion,
                Instant.parse("2026-11-06T12:00:00Z"),
                new Producer("synflux", "1"),
                "tenant-a",
                Optional.empty(),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                Optional.empty(),
                replayClass,
                Optional.empty(),
                new SecurityMetadata(ActorKind.SERVICE, "synflux", "UNCLASSIFIED", 1, List.of()),
                "payload");
    }
}
