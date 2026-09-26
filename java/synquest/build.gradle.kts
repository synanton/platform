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
}
