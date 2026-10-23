package org.synanton.eventing.contract;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Validates command instances against explicitly registered type/schema-version pairs. */
public final class CommandTypeRegistry {

    private final Set<CommandSchemaVersion> declarations;

    public CommandTypeRegistry(Collection<CommandTypeDeclaration> declarations) {
        Objects.requireNonNull(declarations, "declarations");
        Set<CommandSchemaVersion> registered = new HashSet<>();
        for (CommandTypeDeclaration declaration : declarations) {
            Objects.requireNonNull(declaration, "declaration");
            if (!registered.add(new CommandSchemaVersion(
                    declaration.commandType(), declaration.commandVersion()))) {
                throw new IllegalArgumentException("command type/schema-version declarations must be unique");
            }
        }
        this.declarations = Set.copyOf(registered);
    }

    /** Rejects commands with an undeclared command type or schema version. */
    public void validate(Command<?> command) {
        Objects.requireNonNull(command, "command");
        CommandSchemaVersion key = new CommandSchemaVersion(command.commandType(), command.commandVersion());
        if (!declarations.contains(key)) {
            throw new IllegalArgumentException("command type/schema version is not registered");
        }
    }

    private record CommandSchemaVersion(String commandType, int commandVersion) {
    }
}
