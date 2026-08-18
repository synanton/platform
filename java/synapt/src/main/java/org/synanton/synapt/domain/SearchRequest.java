package org.synanton.synapt.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.synanton.common.validation.constraints.QueryText;
import org.synanton.synapt.deprecation.DeprecatedField;

public record SearchRequest(
        @QueryText
        String query,

        @Min(1) @Max(100)
        Integer topK,

        @DeprecatedField(since = "1.17", replacement = "top_k", removalEarliest = "1.20")
        Integer topResults,

        @Valid
        Hints hints
) {
    public SearchRequest(String query, Integer topK, Hints hints) {
        this(query, topK, null, hints);
    }

    public int effectiveTopK() {
        if (topK != null) {
            return topK;
        }
        if (topResults != null) {
            return topResults;
        }
        return 10;
    }
}
