package org.synanton.security.issuance;

/** Issuance audit is a security control: failure must abort token issuance. */
@FunctionalInterface
public interface IssuanceAudit {
    void record(IssuanceEvent event);
}
