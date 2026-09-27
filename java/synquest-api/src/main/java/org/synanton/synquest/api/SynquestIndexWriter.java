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
 */
public interface SynquestIndexWriter {

    CompletionStage<Void> upsert(List<ChunkProjection> projections);

    CompletionStage<Void> delete(GenerationId generationId, Collection<ChunkId> ids);
}
