import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

// Align Testcontainers with the catalog; Spring Boot's BOM otherwise pins the
// older 1.19.x module layout (and its Docker client provider).
dependencyManagement {
    dependencies {
        dependency("org.testcontainers:testcontainers:1.21.4")
        dependency("org.testcontainers:neo4j:1.21.4")
    }
}

dependencies {
    implementation(project(":java:shared:common"))
    implementation(project(":java:ingestion-cache"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.databind)
    implementation(libs.logback.classic)
    implementation(libs.jgrapht.core)
    implementation(libs.neo4j.java.driver)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.neo4j)
}

tasks.named<BootJar>("bootJar") {
    archiveBaseName.set("relix")
}
tasks.named<Jar>("jar") { enabled = false }
