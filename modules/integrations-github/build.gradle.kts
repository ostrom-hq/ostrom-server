// SPDX-License-Identifier: AGPL-3.0-only
// Module integrations-github: the GitHub App (installation per organization, webhooks, created by the manifest flow
// when the environment names none), the head of a pull request read from GitHub for the gates, the `ostrom-gate`
// check run, and each organization's directory (repositories and teams) read from its installation.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:gates"))
            // The cipher that seals the App created by the manifest flow (the instance's secrets key).
            implementation(project(":modules:secrets"))
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
