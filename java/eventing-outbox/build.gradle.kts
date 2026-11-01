plugins {
    `java-library`
}

dependencies {
    api(project(":java:eventing-contract"))
    api(project(":java:eventing-publisher"))
    api(libs.jackson.databind)
    // KafkaOutboxDispatcher ships alongside the broker-agnostic OutboxDispatcher seam
    // in this module, so kafka-clients is a runtime dependency (implementation, not api:
    // consumers construct the dispatcher via KafkaOutboxDispatcher.connect and do not
    // touch KafkaProducer directly).
    implementation(libs.kafka.clients)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
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
