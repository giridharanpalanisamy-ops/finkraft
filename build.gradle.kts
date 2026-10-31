plugins {
    alias(libs.plugins.spring.boot) apply false
}

// Leaf modules only; ":libs", ":services" and ":test-support" are plain containers.
configure(subprojects.filter { it.parent != rootProject }) {
    apply(plugin = "java-library")

    group = "com.finkraft"
    version = "0.1.0"

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    val bom = dependencies.platform(rootProject.libs.spring.boot.bom)
    dependencies {
        "implementation"(bom)
        "testImplementation"(bom)
        "annotationProcessor"(bom)
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.assertj)
        "testRuntimeOnly"(rootProject.libs.junit.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "1g"
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    // Services are executable; one fat jar per service as build/libs/app.jar. The plain jar
    // stays enabled because scenario-tests runs every service in-process.
    pluginManager.withPlugin("org.springframework.boot") {
        tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
            archiveFileName.set("app.jar")
        }
    }
}
