// The server binary: reads the configuration, opens the database, applies every module's migrations, wires Koin
// and serves HTTP on Ktor's CIO engine. Depends on every module of modules/.
plugins {
    id("ostrom.native-module")
}

kotlin {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = "ostrom-server"
            entryPoint = "dev.ostrom.server.app.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(libs.ktor.server.cio)
            // The engine of telemetry's HTTP client (OTLP, Langfuse, PostHog).
            implementation(libs.ktor.client.cio)
            rootProject.subprojects
                .filter { it.path.startsWith(":modules:") }
                .forEach { implementation(project(it.path)) }
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
