plugins {
    `java-library`
}

dependencies {
    api(project(":java:eventing-contract"))
    api(project(":java:eventing-publisher"))
    api(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

tasks.test {
    useJUnitPlatform()
}
