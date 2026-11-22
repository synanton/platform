package org.synanton.relix.provisioning;

import java.time.Instant;
import java.util.Optional;

/** The Cassandra/registry surface used by provisioning. */
public interface ProvisioningStore {
    boolean isRegistered(String tenantId);

    Optional<String> provider(String tenantId);

    void setProvider(String tenantId, String provider, Instant now);

    long sourceVersionCount(String tenantId);

    void registerTenant(String tenantId, Instant now);
}
