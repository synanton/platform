plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

tasks.named<Jar>("jar") {
    enabled = false  // use bootJar instead
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

    implementation(libs.nimbus.jose.jwt)
    implementation(libs.bcrypt)
    implementation(libs.caffeine)
    implementation(libs.argon2.jvm)

    implementation(libs.logback.classic)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
}
