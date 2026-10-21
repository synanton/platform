package org.synanton.synquest.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Makes the effective embedding dimension and truncation state observable at
 * startup. Truncation is opt-in (EMBED_TRUNCATE_DIM), but the gpu-plane profile
 * sets it to 1024 by default; that inversion should be visible, not silent.
 */
@Component
public class EmbeddingShapeStartupLog implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingShapeStartupLog.class);

    private final SynquestProperties properties;

    public EmbeddingShapeStartupLog(SynquestProperties properties) {
        this.properties = properties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        int dim = properties.embedding().dim();
        int truncateDim = properties.embedding().truncateDim();
        if (truncateDim > 0) {
            log.warn(
                    "EMBED_TRUNCATE_DIM={} TRUNCATION ACTIVE: index dim={}; source vectors longer than {} "
                            + "are truncated. This is opt-in Matryoshka behaviour, not a "
                            + "dimension-mismatch fallback.",
                    truncateDim,
                    dim,
                    truncateDim);
        } else {
            log.info(
                    "EMBED_DIM={} EMBED_TRUNCATE_DIM=0 (truncation disabled; a dimension mismatch fails hard)",
                    dim);
        }
    }
}
