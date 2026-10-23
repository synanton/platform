package org.synanton.eventing.contract;

/** Declares one supported command schema version. Commands do not carry event replay policy. */
public record CommandTypeDeclaration(String commandType, int commandVersion) {

    public CommandTypeDeclaration {
        ContractValues.requireText(commandType, "commandType");
        ContractValues.requirePositive(commandVersion, "commandVersion");
    }
}
