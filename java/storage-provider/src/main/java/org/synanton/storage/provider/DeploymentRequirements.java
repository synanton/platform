package org.synanton.storage.provider;

/**
 * What the deployment requires from its providers. Drives startup validation:
 * a requirement the selected adapter cannot meet fails fast with a specific error
 * (008 decision for the Cassandra revision case).
 *
 * @param requireRevisionSemantics true when any path writes via
 *                                 {@code putDocumentRevision} (atomicity required)
 * @param requireDeleteSemantics   true when any path uses delete semantics
 * @param production               true for production enablement (UNVERIFIED
 *                                 capabilities cannot be enabled)
 */
public record DeploymentRequirements(
        boolean requireRevisionSemantics, boolean requireDeleteSemantics, boolean production) {

    /** PoC metadata-only deployment: no revision/delete semantics required. */
    public static DeploymentRequirements metadataOnly(boolean production) {
        return new DeploymentRequirements(false, false, production);
    }

    /** Full revision deployment: atomic revision + delete semantics required. */
    public static DeploymentRequirements fullRevision(boolean production) {
        return new DeploymentRequirements(true, true, production);
    }
}
