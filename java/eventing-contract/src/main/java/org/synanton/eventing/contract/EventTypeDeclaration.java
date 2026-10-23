package org.synanton.eventing.contract;

import java.util.Objects;
import java.util.Optional;

/** The stable replay declaration for one event type, independent of individual event instances. */
public record EventTypeDeclaration(
        String eventType,
        ReplayClass replayClass,
        Optional<String> replayCondition) {

    public EventTypeDeclaration {
        ContractValues.requireText(eventType, "eventType");
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
