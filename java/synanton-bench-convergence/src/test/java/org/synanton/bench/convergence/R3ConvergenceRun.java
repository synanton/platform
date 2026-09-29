package org.synanton.bench.convergence;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * R3 three-leg convergence runner (plain main, invoked via the {@code runR3}
 * JavaExec task — JUnit discovery proved flaky for this class, and a report
 * runner is not a unit test anyway).
 *
 * <p>Compares baseline vs cassandra and baseline vs ydb over runs/*.json
 * with the pinned 028 config. Records verdicts without failing on divergence
 * (R4 routing interprets per pre-decided rules); fails loudly only on
 * harness errors (missing files, schema mismatch, corpus drift).
 *
 * <p>Run: {@code RUNS_DIR=/abs/runs ./gradlew
 * :java:synanton-bench-convergence:runR3}.
 */
public final class R3ConvergenceRun {

    private R3ConvergenceRun() {}

    public static void main(String[] args) throws Exception {
        report("baseline-v1.json", "cassandra-v1.json");
        report("baseline-v1.json", "ydb-v1.json");
        System.out.println("R3-DONE reports in " + runsDir().toAbsolutePath());
    }

    private static Path runsDir() {
        String dir = System.getenv().getOrDefault("RUNS_DIR", "runs");
        Path base = Paths.get(dir);
        if (!Files.isDirectory(base)) {
            base = Paths.get("/home/aminin/workspace/synanton/platform/runs");
        }
        if (!Files.isDirectory(base)) {
            throw new IllegalStateException("runs dir not found: " + dir);
        }
        return base;
    }

    private static void report(String base, String cand) throws Exception {
        Path dir = runsDir();
        ConvergenceConfig config;
        try (InputStream in =
                R3ConvergenceRun.class.getResourceAsStream("/028-convergence-config.yaml")) {
            if (in == null) {
                throw new IllegalStateException("pinned config missing from classpath");
            }
            config = ConvergenceConfig.parse(in);
        }
        RunOutput baseline;
        try (InputStream in = Files.newInputStream(dir.resolve(base))) {
            baseline = RunOutput.parse(in, base);
        }
        RunOutput candidate;
        try (InputStream in = Files.newInputStream(dir.resolve(cand))) {
            candidate = RunOutput.parse(in, cand);
        }
        ConvergenceRunner.Report report = ConvergenceRunner.compare(baseline, candidate, config);
        String md = ConvergenceReport.render(base, cand, report);
        System.out.println(md);
        Path out = dir.resolve(cand.replace(".json", "") + "-convergence.md");
        Files.writeString(out, md);
        System.out.println("R3-REPORT wrote=" + out);
    }
}
