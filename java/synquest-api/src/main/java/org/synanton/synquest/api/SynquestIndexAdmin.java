package org.synanton.synquest.api;

import java.util.concurrent.CompletionStage;

/**
 * Index-lifecycle port (proposal §10.3): deployment-wide privileged
 * administration, not tenant-scoped operations. No {@code SecurityContext}
 * parameter is needed because callers are operators, not tenants — but every
 * method here acts across tenants by design (notably {@code rebuild}), so
 * access must be restricted to the operator plane, never exposed per-tenant.
 */
public interface SynquestIndexAdmin {

    CompletionStage<Void> ensureSchema(SchemaOptions options);

    /**
     * Rebuilds the index for a new generation fully derived from authoritative Synvault
     * state (invariant 35): ordering keys survive by construction (they come from the
     * Synvault commit sequence), the generation flips via
     * {@code options.targetGeneration()}, and generation-scoped deletes from older
     * generations cannot corrupt the new one. Implementations flip the active-generation
     * pointer on success (Cassandra: put, not putIfAbsent).
     */
    CompletionStage<Void> rebuild(RebuildOptions options);

    CompletionStage<IndexStatus> status();
}
