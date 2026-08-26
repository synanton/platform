import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation(project(":java:shared:common"))
    implementation(project(":java:ingestion-cache"))
    implementation(project(":java:synvault"))
    implementation(project(":java:synanton-llm-client"))
    implementation(project(":java:extraction-contract"))
    implementation(libs.protobuf.java)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.stub)

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.tika.core)
    implementation(libs.tika.parsers)
    implementation(libs.mustache.compiler)
    implementation(libs.caffeine)
    implementation(libs.logback.classic)
    implementation(libs.kafka.clients)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
}

tasks.named<BootJar>("bootJar") {
    archiveBaseName.set("synflux")
}
tasks.named<Jar>("jar") { enabled = false }
