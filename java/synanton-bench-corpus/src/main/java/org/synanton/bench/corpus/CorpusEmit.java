package org.synanton.bench.corpus;

import java.nio.file.Path;
import java.util.List;

/**
 * 028a.10 entry point (built early for the .8 cross-process test):
 * {@code CorpusEmit <outdir>} streams documents/chunks/queries JSONL plus
 * per-file sha256 to stdout. Streaming by construction: chunk rows
 * embed-then-write one at a time, so this path — and every Q3 emitter copying
 * it (028b/c/d/e share the constraint) — never holds 160k vectors at once.
 */
public final class CorpusEmit {

    private CorpusEmit() {}

    static final java.util.List<String> FILE_NAMES =
            java.util.List.of("documents.jsonl", "chunks.jsonl", "golden-queries.jsonl");

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        CorpusIo.Corpus corpus = CorpusIo.buildSkeletons();
        List<String> shas = CorpusIo.emitAll(corpus, out);
        String manifest =
                CorpusManifest.emit(corpus, shas, FILE_NAMES).toPrettyString();
        java.nio.file.Files.writeString(out.resolve("manifest.json"), manifest);
        System.out.println("documents.jsonl " + shas.get(0));
        System.out.println("chunks.jsonl " + shas.get(1));
        System.out.println("golden-queries.jsonl " + shas.get(2));
        System.out.println("manifest.json written (paths relative to " + out + ")");
    }
}
