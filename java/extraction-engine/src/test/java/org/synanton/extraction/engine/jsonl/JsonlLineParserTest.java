package org.synanton.extraction.engine.jsonl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsonlLineParserTest {

    private static final String ENTITY_ACME =
            "{\"type\":\"entity\",\"surfaceForm\":\"Acme\",\"proposedType\":\"Organization\"}";
    private static final String ENTITY_BETA =
            "{\"type\":\"entity\",\"surfaceForm\":\"Beta\",\"proposedType\":\"Organization\"}";
    private static final String RELATION =
            "{\"type\":\"relation\",\"subjectForm\":\"Acme\",\"predicate\":\"hasSupplier\","
                    + "\"objectForm\":\"Beta\",\"evidenceQuote\":\"Acme has supplier Beta.\"}";

    @Test
    void acceptsIndependentRecordsAndPreservesLineNumbers() {
        JsonlParseResult result = new JsonlLineParser().parse(ENTITY_ACME + "\n" + RELATION);

        assertThat(result.accepted()).extracting(JsonlLine::lineNumber).containsExactly(1, 2);
        assertThat(result.rejected()).isEmpty();
        assertThat(result.linesTotal()).isEqualTo(2);
    }

    @Test
    void malformedMiddleLineDoesNotPoisonNeighbours() {
        JsonlParseResult result = new JsonlLineParser()
                .parse(ENTITY_ACME + "\n{not-json}\n" + ENTITY_BETA);

        assertThat(result.accepted()).extracting(JsonlLine::lineNumber).containsExactly(1, 3);
        assertThat(result.rejected()).hasSize(1);
        assertThat(result.rejected().getFirst().lineNumber()).isEqualTo(2);
        assertThat(result.rejected().getFirst().reason()).isEqualTo(JsonlFailureReason.JSON_PARSE);
    }

    @Test
    void truncatedFinalLineIsTruncatedWhenFinishReasonLength() {
        JsonlParseResult result = new JsonlLineParser()
                .parse(ENTITY_ACME + "\n{\"type\":\"entity\",\"surfaceForm\":\"Be", "length");

        assertThat(result.accepted()).hasSize(1);
        assertThat(result.rejected()).hasSize(1);
        assertThat(result.rejected().getFirst().lineNumber()).isEqualTo(2);
        assertThat(result.rejected().getFirst().reason()).isEqualTo(JsonlFailureReason.TRUNCATED);
    }

    @Test
    void incompleteFinalLineIsTruncatedWithoutLengthFinishWhenPrefixIsClean() {
        JsonlParseResult result = new JsonlLineParser()
                .parse(ENTITY_ACME + "\n{\"type\":\"entity\",\"surfaceForm\":\"Be", "stop");

        assertThat(result.rejected().getFirst().reason()).isEqualTo(JsonlFailureReason.TRUNCATED);
    }

    @Test
    void malformedCompleteFinalLineWithStopIsJsonParse() {
        JsonlParseResult result = new JsonlLineParser()
                .parse(ENTITY_ACME + "\n{\"type\":\"entity\",}", "stop");

        assertThat(result.rejected().getFirst().reason()).isEqualTo(JsonlFailureReason.JSON_PARSE);
    }

    @Test
    void incompleteFinalLineAfterPriorCorruptionIsNotTruncated() {
        JsonlParseResult result = new JsonlLineParser()
                .parse("{broken}\n{\"type\":\"entity\",\"surfaceForm\":\"Be", "stop");

        assertThat(result.rejected()).extracting(JsonlRejectedLine::reason)
                .containsExactly(JsonlFailureReason.JSON_PARSE, JsonlFailureReason.JSON_PARSE);
    }

    @Test
    void bracesAndEscapedQuotesInsideStringsDoNotCauseFalseConcatenation() {
        String valid = JsonlWriter.write(java.util.Map.of(
                "type", "entity",
                "surfaceForm", "a } { \" b",
                "proposedType", "Concept"));
        assertThat(new JsonlLineParser().parse(valid).accepted()).hasSize(1);

        String malformed = valid.substring(0, valid.length() - 1) + ",}";
        JsonlParseResult result = new JsonlLineParser().parse(malformed);

        assertThat(result.rejected().getFirst().reason()).isEqualTo(JsonlFailureReason.JSON_PARSE);
    }

    @Test
    void detectsConcatenatedObjectsOnOneLine() {
        JsonlParseResult result = new JsonlLineParser().parse(ENTITY_ACME + ENTITY_BETA);

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected().getFirst().reason())
                .isEqualTo(JsonlFailureReason.CONCATENATED_RECORDS);
    }

    @Test
    void rejectsNonObjectAndUnknownTypeAndMissingFields() {
        JsonlParseResult result = new JsonlLineParser().parse("[]\n"
                + "{\"type\":\"other\",\"surfaceForm\":\"Acme\"}\n"
                + "{\"type\":\"entity\",\"surfaceForm\":\"\"}");

        assertThat(result.rejected()).extracting(JsonlRejectedLine::reason)
                .containsExactly(JsonlFailureReason.SCHEMA,
                        JsonlFailureReason.UNKNOWN_RECORD_TYPE,
                        JsonlFailureReason.EMPTY_FIELD);
    }

    @Test
    void rejectsRelationWithoutEvidence() {
        JsonlParseResult result = new JsonlLineParser().parse(
                "{\"type\":\"relation\",\"subjectForm\":\"Acme\","
                        + "\"predicate\":\"hasSupplier\",\"objectForm\":\"Beta\"}");

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected().getFirst().reason()).isEqualTo(JsonlFailureReason.EMPTY_FIELD);
    }

    @Test
    void stripsOuterCodeFences() {
        String fenced = "```json\n" + ENTITY_ACME + "\n" + ENTITY_BETA + "\n```";

        JsonlParseResult result = new JsonlLineParser().parse(fenced);

        assertThat(result.fenceStripped()).isTrue();
        assertThat(result.accepted()).hasSize(2);
        assertThat(result.rejected()).isEmpty();
    }

    @Test
    void countsBlankLinesButKeepsOneBasedSourceLineNumbers() {
        JsonlParseResult result = new JsonlLineParser().parse("\n" + ENTITY_ACME + "\n\n" + ENTITY_BETA);

        assertThat(result.blankLines()).isEqualTo(2);
        assertThat(result.accepted()).extracting(JsonlLine::lineNumber).containsExactly(2, 4);
    }

    @Test
    void enforcesLineByteLimit() {
        JsonlParseResult result = new JsonlLineParser(16).parse(ENTITY_ACME);

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected().getFirst().reason()).isEqualTo(JsonlFailureReason.LINE_TOO_LARGE);
    }

    @Test
    void handlesLargeResponsesWithoutLosingBoundaries() {
        int lines = 5_000;
        StringBuilder content = new StringBuilder(lines * 40);
        for (int i = 0; i < lines; i++) {
            content.append("{\"type\":\"entity\",\"surfaceForm\":\"E").append(i)
                    .append("\",\"proposedType\":\"Concept\"}\n");
        }

        JsonlParseResult result = new JsonlLineParser().parse(content.toString());

        assertThat(result.accepted()).hasSize(lines);
        assertThat(result.rejected()).isEmpty();
        assertThat(result.accepted().get(lines - 1).lineNumber()).isEqualTo(lines);
    }
}
