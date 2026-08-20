package org.synanton.gpu.gateway.dispatch;

import org.synanton.gpu.v1.ExecutionRequest;
import org.synanton.gpu.v1.GetCapacityRequest;
import org.synanton.gpu.v1.CapacityResponse;

public interface ExecutionDispatcher {

    DispatchResult dispatch(ExecutionRequest request, String executionId);

    CapacityResponse capacity(GetCapacityRequest request);
}
