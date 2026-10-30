// SPDX-License-Identifier: AGPL-3.0-only
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
        }
        nativeMain.dependencies {
            implementation(libs.cryptography.openssl3.prebuilt)
        }
        // The JVM target only runs the tests and Kover (never shipped): the JDK provides SHA-256 there.
        jvmMain.dependencies {
            implementation(libs.cryptography.jdk)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
