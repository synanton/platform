package org.synanton.relix.syntology;

/** Read-only Syntology lookup used only for canonical metadata bootstrap. */
public interface SyntologyActiveVersionClient {

    ActiveOntologyVersion getActiveVersion(String tenantId);
}
