package org.synanton.relix.identity;

public record ProjectionProvisioningResult(
        ProjectionProvisioningOutcome outcome,
        CanonicalGraphMetadata metadata,
        String detail) {

    public static ProjectionProvisioningResult failed(String detail) {
        return new ProjectionProvisioningResult(
                ProjectionProvisioningOutcome.FAILED, null, detail);
    }
}
