plugins {
    `java-library`
}

dependencies {
    api(project(":java:synvault-api"))
    api(libs.ydb.sdk.table)

    testImplementation(project(":java:synvault-api"))
    testImplementation(project(":java:synquest-ydb"))
    testImplementation(project(":java:synquest-inmemory"))
    testImplementation(project(":java:storage-provider"))
    testImplementation(testFixtures(project(":java:storage-testkit")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.logback.classic)
}

// Opt-in benchmark gate (YDB-POC-022): -Dydb.bench=true runs YdbWriteBench; unset skips it.
tasks.named<Test>("test") {
    systemProperty("ydb.bench", providers.systemProperty("ydb.bench").getOrElse(""))
    systemProperty("ydb.probe", providers.systemProperty("ydb.probe").getOrElse(""))
    systemProperty("ydb.resilience", providers.systemProperty("ydb.resilience").getOrElse(""))
}
