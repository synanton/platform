package org.synanton.bench.emitter;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A.2 heap-leg entry point: loads a corpus dir, prints counts + version.
 * The 1g subprocess test runs this (028a.8 pattern) — same-JVM-only would
 * prove nothing about the heap bound.
 */
public final class CorpusLoadProbe {

    private CorpusLoadProbe() {}

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        CorpusLoader.Manifest manifest = CorpusLoader.loadManifest(dir);
        AtomicLong chunks = new AtomicLong();
        CorpusLoader.streamChunks(dir, row -> chunks.incrementAndGet());
        System.out.println(
                "corpus_version=" + manifest.corpusVersion() + " chunks=" + chunks.get());
    }
}
