package org.synanton.common.resolution;

import java.util.List;

/** Read-only authoritative graph state used by relation validation (4c.2). */
public interface ResolutionGraphLookup extends ExtractedMentionGraphReadPort {

    default List<ActiveResolution> getActiveResolutions(String tenantId, List<String> mentionIds) {
        throw new UnsupportedOperationException("graph connector does not support active resolution lookup");
    }

}
