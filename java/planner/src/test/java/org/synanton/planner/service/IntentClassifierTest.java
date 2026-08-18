package org.synanton.planner.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentClassifierTest {

    private final IntentClassifier classifier = new IntentClassifier();

    @Test
    void graphKeywordsClassifyAsT1() {
        assertThat(classifier.classify("what is related to Acme Corp?")).isEqualTo("T1");
        assertThat(classifier.classify("find entities connected to GlobalTech")).isEqualTo("T1");
    }

    @Test
    void entityLookupKeywordsClassifyAsT2() {
        assertThat(classifier.classify("who is Jane Doe?")).isEqualTo("T2");
        assertThat(classifier.classify("tell me about Acme Corp")).isEqualTo("T2");
    }

    @Test
    void aggregationKeywordsClassifyAsT4() {
        assertThat(classifier.classify("summarize all supplier contracts")).isEqualTo("T4");
        assertThat(classifier.classify("how many vendors do we have?")).isEqualTo("T4");
    }

    @Test
    void documentSearchKeywordsClassifyAsT3() {
        assertThat(classifier.classify("find documents about procurement")).isEqualTo("T3");
        assertThat(classifier.classify("show reports from Q4")).isEqualTo("T3");
    }

    @Test
    void unknownQueryDefaultsToT3() {
        assertThat(classifier.classify("quarterly budget forecast")).isEqualTo("T3");
    }

    @Test
    void confidenceIsHigherWhenPatternMatches() {
        double matchConf = classifier.confidence("who is Acme?", "T2");
        double noMatchConf = classifier.confidence("budget stuff", "T2");
        assertThat(matchConf).isGreaterThan(noMatchConf);
    }
}
