package org.synanton.synquest.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.synanton.gpu.client.GpuPlaneException;

/**
 * Query embedding failed while {@code synquest.embedding.required=true}. Mapped to HTTP 503
 * so the caller sees the failure instead of a BM25-only answer that looks like a hybrid one.
 * {@link #code()} carries the GPU plane's canonical code where there is one.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class EmbeddingUnavailableException extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    private final String code;

    public EmbeddingUnavailableException(Throwable cause) {
        super("query embedding unavailable and synquest.embedding.required=true: " + cause.getMessage(), cause);
        this.code = cause instanceof GpuPlaneException g ? g.code() : "embedding_unavailable";
    }

    public String code() {
        return code;
    }
}
