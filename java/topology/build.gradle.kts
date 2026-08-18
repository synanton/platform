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
}
