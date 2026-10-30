// Module runners: cloud sessions (one pod per session on Kubernetes, or a local Docker container), their
// reconciler, the runner channel and the terminal relay.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(libs.cryptography.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.server.websockets)
        }
        nativeMain.dependencies {
            implementation(libs.cryptography.openssl3.prebuilt)
            implementation(libs.ktor.client.curl)
        }
        // The JVM target only runs the tests and Kover (never shipped): the JDK provides SHA-256 and CIO the client.
        jvmMain.dependencies {
            implementation(libs.cryptography.jdk)
            implementation(libs.ktor.client.cio)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.websockets)
        }
    }
}
