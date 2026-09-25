package org.synanton.gpu.client;

import org.synanton.llm.LlmClientException;

/**
 * A GPU-plane call that did not produce a usable result. {@link #code()} is the canonical
 * error code (Deployment Plan §16), e.g. {@code circuit_open}, {@code budget_exceeded},
 * {@code status_unavailable}, {@code invalid_result}.
 */
public class GpuPlaneException extends LlmClientException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    private final String code;

    public GpuPlaneException(String code, String message) {
        super(message + " [code=" + code + "]");
        this.code = code;
    }

    public GpuPlaneException(String code, String message, Throwable cause) {
        super(message + " [code=" + code + "]", cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
