package org.synanton.relix.graph;

import org.synanton.common.resolution.CanonicalRelation;
import org.synanton.common.resolution.PromotionResult;

/** Promotion port: consumes only VALID validation rows and writes atomically. */
public interface CanonicalRelationPromotionPort {
    PromotionResult promoteRelation(CanonicalRelation relation);
}
