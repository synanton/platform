package org.synanton.synquest.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A reranked search was requested, but reranking isn't configured or failed. Mapped to HTTP 503,
 * so a caller never gets RRF order labelled as reranked.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class RerankUnavailableException extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    public RerankUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
