package org.synanton.extraction.engine.jsonl;

import java.util.List;

/** Outcome of parsing one model response as JSONL. */
public record JsonlParseResult(
        List<JsonlLine> accepted,
        List<JsonlRejectedLine> rejected,
        int linesTotal,
        int blankLines,
        boolean fenceStripped) {

    public boolean hasAccepted() {
        return !accepted.isEmpty();
    }
}
