package org.synanton.synquest.ontology;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OntologyMapperTest {

    private static final String SHA = "00c7e8fb6e1385ffe97710c26523a720dbe57cd922653bf1d099e7dec051a389";
    private static OntologyMapper loaded;

    private static Path ontologyPath() {
        String configured = System.getenv("ONTOLOGY_V2_PATH");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("demo-data/eval/retrieval-benchmark/ontology-v2.jsonl"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("ontology-v2.jsonl not found from test working directory");
        }
        return root.resolve("demo-data/eval/retrieval-benchmark/ontology-v2.jsonl");
    }

    private static OntologyMapper mapper() throws Exception {
        if (loaded == null) {
            loaded = OntologyMapper.loadFrom(ontologyPath(), SHA);
        }
        return loaded;
    }

    @Test
    void load_expectedRowCount_273() throws Exception {
        assertThat(mapper().size()).isEqualTo(273);
    }

    @Test
    void load_abbreviatedSha_rejected() {
        assertThatThrownBy(() -> OntologyMapper.loadFrom(ontologyPath(), "00c7e8fb…051a389"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be the full 64-char hex SHA");
    }

    @Test
    void load_wrongSha_rejected() {
        assertThatThrownBy(() -> OntologyMapper.loadFrom(ontologyPath(), "0".repeat(64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHA-256 mismatch");
    }

    @Test
    void filter_all_273() throws Exception {
        assertThat(mapper().filter("all", "all", null)).hasSize(273);
    }

    @Test
    void filter_graphUseful_31() throws Exception {
        assertThat(mapper().filter("all", "graph_useful", null)).hasSize(31);
    }

    @Test
    void filter_graphUsefulHard_31() throws Exception {
        assertThat(mapper().filter("all", "graph_useful", "hard")).hasSize(31);
    }

    @Test
    void filter_graphUsefulSoftGlossary_0() throws Exception {
        assertThat(mapper().filter("all", "graph_useful", "soft_glossary")).isEmpty();
    }

    @Test
    void filter_pair_38() throws Exception {
        assertThat(mapper().filter("all", "pair", null)).hasSize(38);
    }

    @Test
    void filter_pairHard_30() throws Exception {
        assertThat(mapper().filter("all", "pair", "hard")).hasSize(30);
    }

    @Test
    void filter_pairSoftGlossary_8() throws Exception {
        assertThat(mapper().filter("all", "pair", "soft_glossary")).hasSize(8);
    }

    @Test
    void filter_pairSoftDoctrinal_0() throws Exception {
        assertThat(mapper().filter("all", "pair", "soft_doctrinal")).isEmpty();
    }

    @Test
    void pairSum_38() throws Exception {
        int hard = mapper().filter("all", "pair", "hard").size();
        int glossary = mapper().filter("all", "pair", "soft_glossary").size();
        int doctrinal = mapper().filter("all", "pair", "soft_doctrinal").size();
        assertThat(hard + glossary + doctrinal).isEqualTo(38);
    }

    @Test
    void classCounts_198_72_3() throws Exception {
        int hard = mapper().filter("all", "all", "hard").size();
        int glossary = mapper().filter("all", "all", "soft_glossary").size();
        int doctrinal = mapper().filter("all", "all", "soft_doctrinal").size();
        assertThat(hard).isEqualTo(198);
        assertThat(glossary).isEqualTo(72);
        assertThat(doctrinal).isEqualTo(3);
        assertThat(hard + glossary + doctrinal).isEqualTo(273);
    }

    @Test
    void findById_missing_empty() throws Exception {
        assertThat(mapper().findById("no-such-id")).isEmpty();
    }
}
