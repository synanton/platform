package org.synanton.synquest.service;

import org.apache.lucene.codecs.KnnVectorsFormat;
import org.springframework.stereotype.Component;
import org.synanton.synquest.config.SynquestProperties;

/**
 * The one place that decides what vector goes into (and is queried against) the dense index.
 * Both {@link LuceneIndexBuilder} and {@link QueryEmbedder} call {@link #fit}, so stored
 * vectors and query vectors always get the same truncation and normalisation (retrieval
 * benchmark plan §6 Phase B1-G, G2).
 *
 * <p>Invalid settings fail at startup, never at the first search:
 * <ul>
 *   <li>{@code dim} must be between 1 and Lucene's KNN cap
 *       ({@link KnnVectorsFormat#DEFAULT_MAX_DIMENSIONS}, 1024 in Lucene 9.11);</li>
 *   <li>{@code truncate-dim}, when set, must equal {@code dim}.</li>
 * </ul>
 *
 * <p>Ingest (synflux) stores native vectors, e.g. 2048-dim. Truncation happens only here, so
 * the same ingested tenant can be indexed at 1024 or 768 with a re-index instead of a
 * re-ingest, and no information is lost in the ingestion cache.
 */
@Component
public class EmbeddingShape {

    public static final int LUCENE_MAX_DIMENSIONS = KnnVectorsFormat.DEFAULT_MAX_DIMENSIONS;

    private final int dim;
    private final int truncateDim;

    @org.springframework.beans.factory.annotation.Autowired
    public EmbeddingShape(SynquestProperties props) {
        this(props.embedding().dim(), props.embedding().truncateDim());
    }

    public EmbeddingShape(int dim, int truncateDim) {
        if (dim < 1 || dim > LUCENE_MAX_DIMENSIONS) {
            throw new IllegalStateException("synquest.embedding.dim (EMBED_DIM) = " + dim + " is outside 1.."
                    + LUCENE_MAX_DIMENSIONS + " (Lucene KNN cap); for larger models set EMBED_TRUNCATE_DIM to cut "
                    + "Matryoshka vectors to <= " + LUCENE_MAX_DIMENSIONS);
        }
        if (truncateDim < 0 || (truncateDim > 0 && truncateDim != dim)) {
            throw new IllegalStateException("synquest.embedding.truncate-dim (EMBED_TRUNCATE_DIM) = " + truncateDim
                    + " must be 0 (off) or equal synquest.embedding.dim (EMBED_DIM) = " + dim);
        }
        this.dim = dim;
        this.truncateDim = truncateDim;
    }

    public int dim() {
        return dim;
    }

    public boolean truncates() {
        return truncateDim > 0;
    }

    /**
     * Returns the index-ready vector: cut to {@code dim} when truncation is on and the vector
     * is longer, then L2-normalised.
     *
     * @throws DimensionMismatchException if the resulting length isn't {@code dim}
     */
    public float[] fit(float[] vec) {
        if (vec == null) {
            throw new DimensionMismatchException(dim, 0);
        }
        float[] v = vec;
        if (truncates() && v.length > truncateDim) {
            v = java.util.Arrays.copyOf(v, truncateDim);
        }
        if (v.length != dim) {
            throw new DimensionMismatchException(dim, vec.length);
        }
        return QueryEmbedder.normalise(v);
    }

    public String describe() {
        return truncates() ? dim + " (truncated)" : String.valueOf(dim);
    }

    /** The vector can't be made {@code dim}-long: wrong model, or truncation needed but off. */
    public static class DimensionMismatchException extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public DimensionMismatchException(int expected, int actual) {
            super("embedding dimension mismatch: index expects " + expected + ", vector has " + actual
                    + (actual > expected ? " (set EMBED_TRUNCATE_DIM=" + expected + " for Matryoshka models)" : ""));
        }
    }
}
