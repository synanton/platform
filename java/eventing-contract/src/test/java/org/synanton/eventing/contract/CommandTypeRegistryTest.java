package org.synanton.eventing.contract;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandTypeRegistryTest {

    @Test
    void validatesDeclaredCommandSchemaVersion() {
        CommandTypeRegistry registry = new CommandTypeRegistry(
                List.of(new CommandTypeDeclaration("GenerateEmbedding", 1)));

        assertThatCode(() -> registry.validate(command("GenerateEmbedding", 1)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> registry.validate(command("GenerateEmbedding", 2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.validate(command("DeleteDocument", 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registersEachCommandSchemaVersionAndRejectsDuplicatePairs() {
        CommandTypeDeclaration versionOne = new CommandTypeDeclaration("GenerateEmbedding", 1);
        CommandTypeDeclaration versionTwo = new CommandTypeDeclaration("GenerateEmbedding", 2);
        CommandTypeRegistry registry = new CommandTypeRegistry(List.of(versionOne, versionTwo));

        assertThatCode(() -> registry.validate(command("GenerateEmbedding", 1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> registry.validate(command("GenerateEmbedding", 2)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new CommandTypeRegistry(List.of(versionOne, versionOne)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Command<String> command(String commandType, int commandVersion) {
        return new Command<>(
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                commandType,
                commandVersion,
                Instant.parse("2026-10-23T12:00:00Z"),
                "tenant-a",
                new SecurityMetadata(
                        ActorKind.SERVICE, "content-cache", "UNCLASSIFIED", 1, List.of()),
                new CommandTarget("document", "document-42"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                Optional.empty(),
                Optional.empty(),
                "payload");
    }
}
