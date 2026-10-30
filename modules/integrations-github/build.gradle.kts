// SPDX-License-Identifier: AGPL-3.0-only
// Module integrations-github: the GitHub App (installation per organization, webhooks), the head of a pull request
// read from GitHub for the gates, and the `ostrom-gate` check run.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:gates"))
            // The organization an install start names (`?org=`): the orgs module's lookup by id or short name.
            implementation(project(":modules:orgs"))
            // RS256 for the App's JWT, HMAC-SHA256 for the webhook signature (OpenSSL, linked by the kernel).
            implementation(libs.cryptography.core)
            // Outbound HTTPS to GitHub: Curl on Linux, Darwin on macOS (CIO has no TLS on Kotlin/Native).
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.client.mock)
        }
    }
}
