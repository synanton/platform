package org.synanton.eventing.contract;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventingContractInvariantTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CORRELATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void eventRejectsMissingRequiredFieldsAndInvalidVersion() {
        assertThatThrownBy(() -> event(null, "ContentPublished", 1, "tenant-a"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> event(EVENT_ID, " ", 1, "tenant-a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event(EVENT_ID, "ContentPublished", 0, "tenant-a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event(EVENT_ID, "ContentPublished", 1, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventRejectsNullForEveryRecordComponent() throws ReflectiveOperationException {
        assertEveryComponentRejectsNull(event(EVENT_ID, "ContentPublished", 1, "tenant-a"));
    }

    @Test
    void eventRequiresConsistentFlatAndNestedTenantScope() {
        Event<String> event = event(EVENT_ID, "ContentPublished", 1, "tenant-a");
        assertThatThrownBy(() -> new Event<>(
                EVENT_ID,
                event.eventType(),
                event.eventVersion(),
                event.occurredAt(),
                event.producer(),
                "tenant-a",
                new TenantReference("tenant-b"),
                event.subjectId(),
                event.correlationId(),
                event.causationId(),
                event.replayClass(),
                event.replayCondition(),
                event.security(),
                event.payload()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandRejectsMissingFieldsInvalidVersionsAndNegativeExpectedVersion() {
        Command<String> command = command(Optional.of(0L));
        assertThatCode(() -> command(Optional.of(0L))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new Command<>(
                null,
                command.commandType(),
                command.commandVersion(),
                command.issuedAt(),
                command.tenantId(),
                command.security(),
                command.target(),
                command.correlationId(),
                command.causationId(),
                command.expectedVersion(),
                command.payload()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Command<>(
                command.commandId(),
                command.commandType(),
                0,
                command.issuedAt(),
                command.tenantId(),
                command.security(),
                command.target(),
                command.correlationId(),
                command.causationId(),
                command.expectedVersion(),
                command.payload()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(Optional.of(-1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandRejectsNullForEveryRecordComponent() throws ReflectiveOperationException {
        assertEveryComponentRejectsNull(command(Optional.empty()));
    }

    @Test
    void securityMetadataRequiresAnActorClassificationAndPositivePolicyVersion() {
        assertThatThrownBy(() -> new SecurityMetadata(
                null, "content-cache", "UNCLASSIFIED", 1, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SecurityMetadata(
                ActorKind.SERVICE, "content-cache", " ", 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecurityMetadata(
                ActorKind.SERVICE, "content-cache", "UNCLASSIFIED", 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecurityMetadata(
                ActorKind.SERVICE, "content-cache", "UNCLASSIFIED", 1, List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecurityMetadata(
                ActorKind.SERVICE,
                "content-cache",
                "UNCLASSIFIED",
                1,
                List.of("customer-data", "customer-data")))
                .isInstanceOf(IllegalArgumentException.class);

        List<String> mutableTags = new ArrayList<>(List.of("retention-30d", "customer-data"));
        SecurityMetadata sorted = new SecurityMetadata(
                ActorKind.SERVICE,
                "content-cache",
                "UNCLASSIFIED",
                1,
                mutableTags);
        mutableTags.clear();
        assertThat(sorted.dataHandlingTags()).containsExactly("customer-data", "retention-30d");
        assertThatThrownBy(() -> sorted.dataHandlingTags().add("new-tag"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nestedContractRecordsRejectNullForEveryComponent() throws ReflectiveOperationException {
        assertEveryComponentRejectsNull(new Producer("content-cache", "1.26"));
        assertEveryComponentRejectsNull(security());
        assertEveryComponentRejectsNull(new TenantReference("tenant-a"));
        assertEveryComponentRejectsNull(new CommandTarget("document", "document-42"));
        assertEveryComponentRejectsNull(new EventTypeDeclaration(
                "ContentPublished", ReplayClass.REPLAY_SAFE, Optional.empty()));
    }

    @Test
    void conditionalReplayRequiresAConditionAndOtherClassesRejectOne() {
        assertThatThrownBy(() -> new EventTypeDeclaration(
                "ContentPublished", ReplayClass.REPLAY_WITH_POLICY, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventTypeDeclaration(
                "ContentPublished", ReplayClass.REPLAY_SAFE, Optional.of("operator approval")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventTypeDeclaration(
                "ContentPublished", ReplayClass.REPLAY_WITH_POLICY, Optional.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eventWithReplay(ReplayClass.REPLAY_WITH_POLICY, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eventWithReplay(ReplayClass.REPLAY_WITH_POLICY, Optional.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eventWithReplay(ReplayClass.REPLAY_SAFE, Optional.of("operator approval")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Event<String> event(UUID id, String type, int version, String tenantId) {
        return new Event<>(
                id,
                type,
                version,
                Instant.parse("2026-10-23T12:00:00Z"),
                new Producer("content-cache", "1.26"),
                tenantId,
                Optional.empty(),
                CORRELATION_ID,
                Optional.empty(),
                ReplayClass.REPLAY_SAFE,
                Optional.empty(),
                security(),
                "payload");
    }

    private static Event<String> eventWithReplay(ReplayClass replayClass, Optional<String> replayCondition) {
        return new Event<>(
                EVENT_ID,
                "ContentPublished",
                1,
                Instant.parse("2026-10-23T12:00:00Z"),
                new Producer("content-cache", "1.26"),
                "tenant-a",
                Optional.empty(),
                CORRELATION_ID,
                Optional.empty(),
                replayClass,
                replayCondition,
                security(),
                "payload");
    }

    private static Command<String> command(Optional<Long> expectedVersion) {
        return new Command<>(
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "GenerateEmbedding",
                1,
                Instant.parse("2026-10-23T12:00:00Z"),
                "tenant-a",
                security(),
                new CommandTarget("document", "document-42"),
                CORRELATION_ID,
                Optional.empty(),
                expectedVersion,
                "payload");
    }

    private static SecurityMetadata security() {
        return new SecurityMetadata(
                ActorKind.SERVICE, "content-cache", "UNCLASSIFIED", 1, List.of());
    }

    private static void assertEveryComponentRejectsNull(Record validRecord)
            throws ReflectiveOperationException {
        RecordComponent[] components = validRecord.getClass().getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            parameterTypes[index] = components[index].getType();
            arguments[index] = components[index].getAccessor().invoke(validRecord);
        }
        Constructor<?> canonicalConstructor = validRecord.getClass().getDeclaredConstructor(parameterTypes);
        for (int index = 0; index < components.length; index++) {
            if (components[index].getType().isPrimitive()) {
                continue;
            }
            Object[] invalidArguments = arguments.clone();
            invalidArguments[index] = null;
            int componentIndex = index;
            assertThatThrownBy(() -> canonicalConstructor.newInstance(invalidArguments))
                    .as("null %s must be rejected", components[componentIndex].getName())
                    .isInstanceOf(InvocationTargetException.class)
                    .hasCauseInstanceOf(NullPointerException.class);
        }
    }
}
