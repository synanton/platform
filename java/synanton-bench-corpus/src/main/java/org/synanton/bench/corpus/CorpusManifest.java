package org.synanton.bench.corpus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 028a.9 manifest (Gap C schema). Versions come from single sources —
 * {@link ModuleInfo#GENERATOR_VERSION} and {@link #CORPUS_VERSION} — never
 * string literals at call sites, so code and manifest cannot silently diverge.
 *
 * <p>Joint-bump enforcement (watch item 1): the bump step itself is human, but
 * silent logic change is made impossible without test failure — the recorded
 * file-sha test (DeterminismTest) trips on ANY output change, and this
 * manifest embeds those shas. An implementer who changes generation must
 * update shas and, by the joint rule asserted in {@link #jointBumpRule()},
 * bump both versions together. No silent path exists.
 */
public final class CorpusManifest {

    /** Frozen corpus version (v2+ on any generator-logic change, §6c). */
    public static final String CORPUS_VERSION = "ydb-poc-corpus-v1";

    /** Corpus seed (spec §6). */
    public static final int SEED = 42;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CorpusManifest() {}

    /** Machine-readable statement of the joint-bump rule (asserted in test). */
    public static String jointBumpRule() {
        return "generator-logic change => generator.version AND corpus_version bump together; never silent re-emit";
    }

    public static ObjectNode emit(
            CorpusIo.Corpus corpus, List<String> fileShas, List<String> fileNames) {
        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("corpus_version", CORPUS_VERSION);
        manifest.put("seed", SEED);
        ObjectNode counts = MAPPER.createObjectNode();
        counts.put("documents", corpus.docs().size());
        counts.put("chunks", corpus.chunks().size());
        counts.put("queries", corpus.queries().size());
        manifest.set("counts", MAPPER.valueToTree(counts));
        Map<String, Integer> legCounts = new LinkedHashMap<>();
        corpus.queries()
                .forEach(q -> legCounts.put(q.queryId(), q.eligibleChunkIds().size()));
        manifest.set("leg_eligible_counts", MAPPER.valueToTree(legCounts));
        ObjectNode files = MAPPER.createObjectNode();
        for (int i = 0; i < fileNames.size(); i++) {
            ObjectNode f = MAPPER.createObjectNode();
            f.put("path", fileNames.get(i));
            f.put("sha256", fileShas.get(i));
            files.set(fileNames.get(i), f);
        }
        manifest.set("files", MAPPER.valueToTree(files));
        ObjectNode generator = MAPPER.createObjectNode();
        generator.put("name", ModuleInfo.NAME);
        generator.put("version", ModuleInfo.GENERATOR_VERSION);
        manifest.set("generator", MAPPER.valueToTree(generator));
        manifest.put("joint_bump_rule", jointBumpRule());
        return manifest;
    }
}
