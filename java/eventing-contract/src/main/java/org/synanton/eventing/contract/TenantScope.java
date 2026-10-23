package org.synanton.eventing.contract;

import java.util.Objects;

/** Contract-boundary tenant checks shared by producers and workflow consumers. */
public final class TenantScope {

    private TenantScope() {
    }

    /** Fails closed unless the message tenant exactly matches the already-verified caller scope. */
    public static <T extends TenantScopedMessage> T requireMatches(String expectedTenantId, T message) {
        Objects.requireNonNull(message, "message");
        ContractValues.requireTenantId(expectedTenantId);
        ContractValues.requireTenantId(message.tenantId());
        if (!expectedTenantId.equals(message.tenantId())) {
            throw new TenantScopeMismatchException();
        }
        return message;
    }
}
