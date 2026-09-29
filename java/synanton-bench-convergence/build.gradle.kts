plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.databind)
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

// Opt-in R3 gate: -Dr3.run=true runs the convergence report; unset skips.
tasks.named<Test>("test") {
    systemProperty("r3.run", providers.systemProperty("r3.run").getOrElse(""))
}

// R3 report runner (plain JVM — no test-worker wall, no discovery flakiness).
// RUNS_DIR=/abs/runs ./gradlew :java:synanton-bench-convergence:runR3
tasks.register<JavaExec>("runR3") {
    group = "benchmark"
    description = "R3 convergence report over runs/*.json."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "org.synanton.bench.convergence.R3ConvergenceRun"
}
