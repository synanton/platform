plugins {
    `java-library`
}

dependencies {
    api(project(":java:eventing-contract"))
    api(project(":java:eventing-publisher"))
    api(libs.jackson.databind)
    // Kafka client backs KafkaOutboxDispatcher only; compileOnly keeps the
    // broker-agnostic OutboxDispatcher seam usable by consumers without a broker.
    compileOnly(libs.kafka.clients)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.kafka.clients)
    testImplementation(libs.postgresql)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.redpanda)
    testImplementation(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("e2e")
    }
}

tasks.register<Test>("e2eTest") {
    description = "Runs same-transaction wiring and publish -> Redpanda -> consume E2E against live containers."
    group = "verification"
    useJUnitPlatform {
        includeTags("e2e")
    }
    shouldRunAfter(tasks.test)
}
