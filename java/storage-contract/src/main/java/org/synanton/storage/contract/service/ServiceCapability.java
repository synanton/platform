package org.synanton.storage.contract.service;

/** Compiled service capabilities. A config file or token cannot add an entry. */
public enum ServiceCapability {
    CONTENT_RECONCILE_TENANT("content.reconcile"),
    CONTENT_RECONCILE_CROSS_TENANT("content.reconcile.cross_tenant"),
    CONTENT_MUTATE_TENANT("content.mutate"),
    MIGRATION_READ_TENANT("migration.read"),
    SEARCH_REINDEX_TENANT("search.reindex");

    private final String claimName;

    ServiceCapability(String claimName) {
        this.claimName = claimName;
    }

    public String claimName() {
        return claimName;
    }

    /**
     * Closed mapping: every emitted claim must resolve to a compiled capability.
     * Unknown claims fail at issuance/verification; they never become a silent
     * empty capability set at read time.
     */
    public static ServiceCapability fromClaim(String claim) {
        if (claim == null || claim.isBlank()) {
            throw new ServiceAuthorizationException("blank capability claim");
        }
        for (ServiceCapability capability : values()) {
            if (capability.claimName.equals(claim)) {
                return capability;
            }
        }
        throw new ServiceAuthorizationException("unknown capability claim: " + claim);
    }
}
