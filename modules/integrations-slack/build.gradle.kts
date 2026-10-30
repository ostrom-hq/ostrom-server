// SPDX-License-Identifier: AGPL-3.0-only
// Module integrations-slack: the requests of the gates as direct messages on Slack, and the yes of a small push
// from its buttons (signed interactions).
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:gates"))
            // HMAC-SHA256 for the interactions' signature (OpenSSL, linked by the kernel).
            implementation(libs.cryptography.core)
            // Outbound HTTPS to Slack: Curl on Linux, Darwin on macOS (CIO has no TLS on Kotlin/Native).
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.client.mock)
        }
    }
}
