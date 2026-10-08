package org.synanton.synquest.api;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletionStage;
import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.GenerationId;

/**
 * Projection-mutation port (proposal §10.3). Consumers read from Eventing 1.27 and
 * apply changes here. {@code delete} is generation-scoped so a rebuild cannot be
 * corrupted by late deletes from a previous generation.
 *
 * <p>Ordering-key + generation semantics (SYN-VECTOR-001 B1.2): every projection
 * carries a monotonic {@code orderingKey} (per tenant/doc/chunk, from the Synvault
 * commit sequence — never wall-clock) and a {@code generationId}. A rebuild
 * ({@link SynquestIndexAdmin#rebuild}) replays authoritative Synvault state, so
 * ordering keys survive it by construction; the generation flips via
 * {@code RebuildOptions.targetGeneration}, and generation-scoped deletes from an
 * older generation can never corrupt the new one.
 *
 * <p>Narrowing contract (SYN-VECTOR-001 B1.2): the port accepts any
 * {@link Projection}, but adapters implement per-kind support. An adapter receiving
 * a projection kind it does not implement must fail with
 * {@code UNSUPPORTED} — never silently drop it. Chunk-projection support is
 * universal; vector-projection writers land per adapter in B3.
 */
public interface SynquestIndexWriter {

    CompletionStage<Void> upsert(List<? extends Projection> projections);

    CompletionStage<Void> delete(GenerationId generationId, Collection<ChunkId> ids);
}
