// End-to-end scenarios (spec section 11): every service and both mocks run in this JVM,
// against Testcontainers Postgres and Kafka.
dependencies {
    testImplementation(project(":services:management-service"))
    testImplementation(project(":services:invoice-handler"))
    testImplementation(project(":services:validator-service"))
    testImplementation(project(":services:integration-service"))
    testImplementation(project(":services:status-mapper"))
    testImplementation(project(":services:webhook-handler"))
    testImplementation(project(":services:scheduler-service"))
    testImplementation(project(":test-support:mock-asp-a"))
    testImplementation(project(":test-support:mock-asp-b"))
    testImplementation(project(":services:validation-agent"))
    testImplementation(project(":libs:asp-adapters"))
    testImplementation(project(":libs:event-contracts"))

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.kafka)
    testImplementation(libs.spring.boot.starter.jdbc)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.awaitility)
}

tasks.test {
    maxHeapSize = "3g"
}

tasks.test {
    // ApiDocsTest writes the served OpenAPI specs here, so docs/api always matches the code.
    systemProperty("finkraft.openapi.out", rootProject.layout.projectDirectory.dir("docs/api").asFile.absolutePath)
    outputs.dir(rootProject.layout.projectDirectory.dir("docs/api"))
}
