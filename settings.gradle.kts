plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "finkraft"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include(
    "libs:canonical-model",
    "libs:state-machine",
    "libs:event-contracts",
    "libs:adapter-api",
    "libs:asp-adapters",
    "libs:service-support",

    "services:management-service",
    "services:invoice-handler",
    "services:validator-service",
    "services:integration-service",
    "services:status-mapper",
    "services:webhook-handler",
    "services:scheduler-service",
    "services:validation-agent",

    "test-support:mock-asp-a",
    "test-support:mock-asp-b",
    "test-support:scenario-tests",
)
