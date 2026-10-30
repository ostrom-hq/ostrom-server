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
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
