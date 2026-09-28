import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation(project(":java:shared:common"))
    implementation(project(":java:ingestion-cache"))
    implementation(project(":java:synanton-llm-client"))
    implementation(project(":java:gpu-client"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.logback.classic)

    implementation(libs.lucene.core)
    implementation(libs.lucene.analysis.common)
    implementation(libs.lucene.queryparser)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    // 028b.1b bench wrapper (test sources, beside BaselineBench).
    testImplementation(project(":java:synquest-api"))
    testImplementation(project(":java:storage-contract"))
    testImplementation(project(":java:synanton-bench-emitter"))
    testImplementation(project(":java:synanton-bench-baseline"))
}

tasks.named<BootJar>("bootJar") {
    archiveBaseName.set("synquest")
}
tasks.named<Jar>("jar") { enabled = false }

// Opt-in benchmark gate (YDB-POC-006): -Dydb.bench=true runs BaselineBench; unset skips it.
tasks.named<Test>("test") {
    systemProperty("ydb.bench", providers.systemProperty("ydb.bench").getOrElse(""))
    systemProperty("ydb.bench.docs", providers.systemProperty("ydb.bench.docs").getOrElse("2000"))
    systemProperty("ydb.bench.chunks", providers.systemProperty("ydb.bench.chunks").getOrElse("8"))
    systemProperty("bench.run.baseline", providers.systemProperty("bench.run.baseline").getOrElse(""))
    // 028b.8 documented run holds 50 Lucene indexes + query fan-out in worker;
    // 10g cap (64G host). Cap only, not tuning.
    maxHeapSize = "10g"
}
