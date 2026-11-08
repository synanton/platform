package org.synanton.synflux.extraction.graph;

import java.util.List;

/** Port for the Relix isolated extracted-mention write API. */
public interface GraphWriteClient {
    void upsertMentions(List<GraphMention> mentions);
    void upsertRelations(List<GraphRelation> relations);
}
