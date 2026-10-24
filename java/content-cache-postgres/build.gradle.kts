plugins {
    `java-library`
}

dependencies {
    api(project(":java:content-cache-api"))
    implementation(project(":java:storage-contract"))
    api(libs.postgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.logback.classic)
    testRuntimeOnly(libs.postgresql)
}

tasks.test {
    useJUnitPlatform()
}
