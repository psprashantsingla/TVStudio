plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    api(project(":core"))
    implementation("dev.mobile:dadb:2.0.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.3")
}

tasks.test {
    useJUnitPlatform()
    // The emulator test needs these, and only runs when CI sets them.
    environment("TVSTUDIO_E2E_HOST", System.getenv("TVSTUDIO_E2E_HOST") ?: "")
    outputs.upToDateWhen { System.getenv("TVSTUDIO_E2E_HOST").isNullOrEmpty() }
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
