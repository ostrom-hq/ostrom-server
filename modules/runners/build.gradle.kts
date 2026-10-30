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
            implementation(libs.cryptography.openssl)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.curl)
            implementation(libs.ktor.server.websockets)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.websockets)
        }
    }
}
