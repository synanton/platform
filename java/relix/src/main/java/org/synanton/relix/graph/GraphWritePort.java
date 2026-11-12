package org.synanton.relix.graph;

import org.synanton.common.resolution.CanonicalRelation;
import org.synanton.common.resolution.GraphIdentity;
import org.synanton.common.resolution.PromotionResult;
import org.synanton.common.resolution.SourceRevisionIdentity;
import org.synanton.relix.api.dto.Edge;
import org.synanton.relix.api.dto.Entity;

/**
 * Canonical graph write contract (:Entity/:REL). Every operation requires an
 * explicit GraphIdentity; extraction-pipeline writes belong on
 * ExtractedMentionGraphWritePort.
 */
public interface GraphWritePort {

    void loadTenant(GraphIdentity identity);

    void upsertEntity(GraphIdentity identity, Entity entity);

    void upsertRelation(GraphIdentity identity, Edge relation);

    PromotionResult promoteRelation(GraphIdentity identity, CanonicalRelation relation);

    /** Promotion guarded by the source revision generation fence. */
    default PromotionResult promoteRelation(GraphIdentity identity, CanonicalRelation relation,
                                            SourceRevisionIdentity sourceRevision) {
        throw new UnsupportedOperationException("graph connector does not support generation-fenced promotion");
    }
}
