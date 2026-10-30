// SPDX-License-Identifier: AGPL-3.0-only
// Module notifications: an approver hears of a request on their phone (Expo push) and by email (Resend).
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:gates"))
            // Outbound HTTPS to Expo and Resend: Curl on Linux, Darwin on macOS (CIO has no TLS on Kotlin/Native).
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.client.mock)
        }
    }
}
