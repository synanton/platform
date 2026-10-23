plugins {
    `java-library`
}

dependencies {
    api(libs.jackson.annotations)
    api(libs.jackson.databind)
    api(libs.jackson.datatype.jdk8)
    api(libs.jackson.datatype.jsr310)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

tasks.test {
    useJUnitPlatform()
}
