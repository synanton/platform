plugins {
    `java-library`
}

dependencies {
    // Shared emitter: port APIs only. No adapter imports — 028c/d/e wire
    // adapters against this module; adapter-specific logic here is drift.
    api(project(":java:synquest-api"))
    api(libs.jackson.databind)
    testImplementation(project(":java:synquest-inmemory"))
    testImplementation(project(":java:synquest-ydb"))
    testImplementation(project(":java:synvault-inmemory"))
    testImplementation(project(":java:synquest-cassandra"))
    testImplementation(project(":java:synquest-postgres"))
    testImplementation(project(":java:synvault-postgres"))
    testImplementation(libs.postgresql)
    testImplementation(project(":java:synanton-bench-convergence"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

// Opt-in full-leg gate (B.2): -Dbench.run.024a=true runs Full024ARun; unset
// skips it. System properties do NOT cross into the test worker otherwise —
// this forwarding is why the flag works (same pattern as ydb.bench).
// 2g heap: documented-run requirement (160k HNSW + stored fields in worker;
// OOM previously surfaced as silent SKIP at query 100/120). Margin, not tuning.
// Raised to 10g per runbook (64G host; corpus 762M on disk, index ~430M;
// in-worker readers + HNSW dominate). Revisit only with measured OOM evidence.
tasks.named<Test>("test") {
    systemProperty("bench.run.024a", providers.systemProperty("bench.run.024a").getOrElse(""))
    systemProperty("bench.run.024b.sanity", providers.systemProperty("bench.run.024b.sanity").getOrElse(""))
    systemProperty("bench.run.h2matrix", providers.systemProperty("bench.run.h2matrix").getOrElse(""))
    maxHeapSize = "10g"
}

// Canonical documented-run invocation (no test-worker wall; plain JVM):
//   LEG_ARGS="--engine ydb --corpus /tmp/corpus-v1 --out /abs/runs/ydb-v1.json" \
//     ./gradlew :java:synanton-bench-emitter:runLeg
// (-PlegArgs quoting is shell-fragile; env is exact.) Detached: setsid the
// gradle client itself; --out must be absolute.
tasks.register<JavaExec>("runLeg") {
    group = "benchmark"
    description = "Documented benchmark leg run (CLI.1)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "org.synanton.bench.emitter.RunLeg"
    val legArgs =
        System.getenv("LEG_ARGS")
            ?: (if (project.hasProperty("legArgs")) project.property("legArgs") as String else "")
    if (legArgs.isNotBlank()) {
        args = legArgs.trim().split("\\s+".toRegex())
    }
}
