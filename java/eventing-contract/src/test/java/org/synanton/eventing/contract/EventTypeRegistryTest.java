package org.synanton.eventing.contract;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTypeRegistryTest {

    @Test
    void validatesMessageReplayClassificationAgainstItsTypeDeclaration() {
        EventTypeDeclaration declaration = new EventTypeDeclaration(
                "SourcePublished", ReplayClass.REPLAY_WITH_POLICY, Optional.of("source-version-retained"));
        EventTypeRegistry registry = new EventTypeRegistry(List.of(declaration));
        Event<String> event = event(
                "SourcePublished", ReplayClass.REPLAY_WITH_POLICY, Optional.of("source-version-retained"));

        assertThatCode(() -> registry.validate(event)).doesNotThrowAnyException();
    }

    @Test
    void acceptsEveryClassificationFrozenByDesign127() {
        for (ReplayClass replayClass : ReplayClass.values()) {
            Optional<String> condition = replayClass == ReplayClass.REPLAY_WITH_POLICY
                    ? Optional.of("operator approval")
                    : Optional.empty();
            EventTypeDeclaration declaration = new EventTypeDeclaration("Event-" + replayClass, replayClass, condition);
            EventTypeRegistry registry = new EventTypeRegistry(List.of(declaration));

            assertThatCode(() -> registry.validate(event(declaration.eventType(), replayClass, condition)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsUndeclaredDuplicateOrMismatchedEventTypeDeclarations() {
        EventTypeDeclaration declaration = new EventTypeDeclaration(
                "SourcePublished", ReplayClass.REPLAY_SAFE, Optional.empty());
        EventTypeRegistry registry = new EventTypeRegistry(List.of(declaration));

        assertThatThrownBy(() -> registry.validate(
                event("SourceDeleted", ReplayClass.REPLAY_SAFE, Optional.empty())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.validate(
                event("SourcePublished", ReplayClass.NOT_REPLAYABLE, Optional.empty())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventTypeRegistry(List.of(declaration, declaration)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Event<String> event(
            String eventType, ReplayClass replayClass, Optional<String> replayCondition) {
        return new Event<>(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                eventType,
                1,
                Instant.parse("2026-10-23T12:00:00Z"),
                new Producer("content-cache", "1.26"),
                "tenant-a",
                Optional.empty(),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                Optional.empty(),
                replayClass,
                replayCondition,
                new SecurityMetadata(
                        ActorKind.SERVICE, "content-cache", "UNCLASSIFIED", 1, List.of()),
                "payload");
    }
}
