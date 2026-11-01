plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

tasks.named<Jar>("jar") {
    enabled = false  // use bootJar
}

dependencies {
    implementation(project(":java:shared:common"))
    implementation(project(":java:eventing-outbox"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    implementation(libs.logback.classic)

    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly(libs.h2)  // H2 for unit tests (POSIX mode)
    testImplementation(libs.postgresql)
    testImplementation(libs.kafka.clients)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.redpanda)
}

tasks.test {
    useJUnitPlatform {
        excludeTags("e2e")
    }
}

tasks.register<Test>("e2eTest") {
    description = "Runs the publish -> Redpanda -> consume E2E against testcontainers Postgres + Redpanda."
    group = "verification"
    useJUnitPlatform {
        includeTags("e2e")
    }
    shouldRunAfter(tasks.test)
}
