package org.synanton.relix.identity;

import org.synanton.common.resolution.GraphIdentity;

public interface GraphIdentityResolver {

    GraphIdentity resolveCanonical(String tenantId);
}
