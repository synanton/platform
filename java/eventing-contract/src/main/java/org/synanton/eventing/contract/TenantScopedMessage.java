package org.synanton.eventing.contract;

import java.util.Optional;
import java.util.UUID;

/** Common identity and scope contract shared by events and commands. */
public interface TenantScopedMessage {

    UUID messageId();

    String tenantId();

    UUID correlationId();

    Optional<UUID> causationId();
}
