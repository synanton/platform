package org.synanton.relix.provisioning;

/** Relix canonical graph metadata provisioning. */
public interface GraphProvisioner {
    GraphProvisionOutcome provision(String tenantId);
}
