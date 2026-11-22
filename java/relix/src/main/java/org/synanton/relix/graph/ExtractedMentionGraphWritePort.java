package org.synanton.relix.graph;

import java.util.List;

/**
 * RAKG extracted-mention/relation write contract (ADR-022 / PR #3).
 * It never creates canonical :Entity nodes or :REL edges; those belong to the
 * canonical graph and PR #4 resolution.
 */
public interface ExtractedMentionGraphWritePort {

    default int upsertMentions(List<ExtractedMention> mentions) {
        throw new UnsupportedOperationException("graph connector does not support extracted-mention writes");
    }

    default int upsertRelations(List<ExtractedRelation> relations) {
        throw new UnsupportedOperationException("graph connector does not support extracted-relation writes");
    }
}
