plugins {
    `java-library`
}

// Extraction ports/adapters. Main sources use the JDK only (the CLI hand-writes
// its JSON) so the evaluator can run it without a framework classpath.
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

tasks.test {
    useJUnitPlatform()
}
