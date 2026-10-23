package org.synanton.eventing.contract;

import java.util.Objects;
import java.util.Optional;

/** Replay declaration for one event type/schema-version pair; replay policy remains type-wide. */
public record EventTypeDeclaration(
        String eventType,
        int eventVersion,
        ReplayClass replayClass,
        Optional<String> replayCondition) {

    public EventTypeDeclaration {
        ContractValues.requireText(eventType, "eventType");
        ContractValues.requirePositive(eventVersion, "eventVersion");
        Objects.requireNonNull(replayClass, "replayClass");
        Objects.requireNonNull(replayCondition, "replayCondition");
        replayCondition.ifPresent(value -> ContractValues.requireText(value, "replayCondition"));
        if (replayClass == ReplayClass.REPLAY_WITH_POLICY && replayCondition.isEmpty()) {
            throw new IllegalArgumentException("REPLAY_WITH_POLICY requires a documented condition");
        }
        if (replayClass != ReplayClass.REPLAY_WITH_POLICY && replayCondition.isPresent()) {
            throw new IllegalArgumentException("replayCondition is only valid for REPLAY_WITH_POLICY");
        }
    }
}
