package org.synanton.synflux.extraction.graph;

import java.util.List;

/** Port for the Relix isolated extracted-mention write API. */
public interface GraphWriteClient {
    int upsertMentions(List<GraphMention> mentions);
    int upsertRelations(List<GraphRelation> relations);
}
