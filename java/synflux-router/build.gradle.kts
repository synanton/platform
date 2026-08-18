import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation(project(":java:shared:common"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.kafka.clients)
    implementation(libs.jackson.databind)
    implementation(libs.logback.classic)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
}

tasks.named<BootJar>("bootJar") {
    archiveBaseName.set("synflux-router")
}
tasks.named<Jar>("jar") { enabled = false }
