package org.synanton.synquest.ydb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class YdbSearchSchemaTest {

    @Test
    void rowIdFailureNamesTheFlag() {
        assertThat(YdbSearchSchema.interpret("ALTER ...", "requires the __ydb_row_id doc_id feature"))
                .contains("enable_fulltext_index_row_id");
    }

    @Test
    void prefixedFailureNamesTheFlag() {
        assertThat(YdbSearchSchema.interpret("ALTER ...", "Prefixed fulltext/json index support is disabled"))
                .contains("enable_fulltext_index_prefix");
    }

    @Test
    void wrongSubtypePointsAtDocs() {
        assertThat(YdbSearchSchema.interpret("ALTER ...", "VECTOR_COSINE index subtype is not supported"))
                .contains("vector_kmeans_tree");
    }

    @Test
    void unknownFailurePassesThrough() {
        assertThat(YdbSearchSchema.interpret("CREATE ...", "some other error")).contains("some other error");
    }
}
