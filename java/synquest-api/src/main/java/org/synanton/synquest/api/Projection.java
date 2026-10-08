package org.synanton.synquest.api;

import org.synanton.storage.contract.ChunkId;
import org.synanton.storage.contract.GenerationId;

/**
 * Projection-unit contract shared by chunk and vector projections (SYN-VECTOR-001 B1).
 * Sealed so the writer port can accept any projection kind through one method
 * ({@code upsert(List<? extends Projection>)} in B1.2) without erasure clashes
 * between overloads.
 */
public sealed interface Projection permits ChunkProjection, VectorProjection {

    ChunkId chunkId();

    long orderingKey();

    GenerationId generationId();
}
