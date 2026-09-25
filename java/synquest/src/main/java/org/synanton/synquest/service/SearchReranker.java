package org.synanton.synquest.service;

import java.util.List;

/**
 * Cross-encoder reranking port for {@link SearchService} (retrieval benchmark B2, T10). Returns
 * one relevance score per passage, in input order. Implementations fail closed and throw
 * instead of returning partial scores.
 */
public interface SearchReranker {

    double[] scores(String tenant, String query, List<String> passages);

    /** The logical model, recorded on the response and in benchmark run records. */
    String model();
}
