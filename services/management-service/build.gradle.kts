plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":libs:service-support"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.springdoc.webmvc.ui)
    implementation(libs.spring.boot.starter.jdbc)
    runtimeOnly(libs.postgresql)
    implementation(libs.spring.boot.starter.validation)
    implementation(project(":libs:asp-adapters"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
}
