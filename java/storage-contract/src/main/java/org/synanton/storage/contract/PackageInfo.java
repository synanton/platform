package org.synanton.storage.contract;

/**
 * Marks the single-source-of-truth home for cross-port types used by both
 * {@code synvault-api} and {@code synquest-api} (YDB-POC-037).
 *
 * <p>Rules enforced by tests in this module and (once the API modules exist in
 * YDB-POC-011) by an ArchUnit rule in CI:
 *
 * <ul>
 *   <li>Both {@code *-api} modules may depend on this module.</li>
 *   <li>Neither {@code *-api} module may depend on the other.</li>
 *   <li>This module has no provider-specific imports (no CQL/YQL, no Cassandra/YDB SDKs).</li>
 * </ul>
 */
public final class PackageInfo {
    private PackageInfo() {
    }

    public static final String PACKAGE = "org.synanton.storage.contract";
}
