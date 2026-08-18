plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.databind)
    api(libs.slf4j.api)
    implementation(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
}

tasks.test {
    useJUnitPlatform()
}
