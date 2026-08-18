package org.synanton.router;

import java.util.Map;
import java.util.Set;

public record RouterStatus(
        Map<String, TopicInfo> topics,
        Set<String> pausedTenants
) {
    public record TopicInfo(int partitions, long consumerLag) {}
}
