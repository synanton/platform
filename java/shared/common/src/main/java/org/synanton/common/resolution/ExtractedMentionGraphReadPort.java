package org.synanton.common.resolution;

import java.util.List;
import java.util.Optional;

/**
 * RAKG extracted-mention/relation read contract. Extraction metadata is the
 * scope; canonical graph operations use the identity-aware read port.
 */
public interface ExtractedMentionGraphReadPort {

    default Optional<ExtractedRelationView> getExtractedRelation(String tenantId, String relationId) {
        throw new UnsupportedOperationException("graph connector does not support extracted-relation lookup");
    }

    default List<ExtractedRelationView> listExtractedRelations(String tenantId, String sourceVersionId) {
        throw new UnsupportedOperationException("graph connector does not support extracted-relation listing");
    }
}
