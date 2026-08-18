import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation(project(":java:shared:common"))
    implementation(project(":java:ingestion-cache"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.aws.s3)
    implementation(libs.caffeine)
    implementation(libs.logback.classic)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.named<BootJar>("bootJar") {
    archiveBaseName.set("synvault")
    // Reclassify bootJar so the plain jar can be used as a dependency by synflux
    archiveClassifier.set("boot")
}
tasks.named<Jar>("jar") {
    enabled = true
    archiveClassifier.set("")
}
