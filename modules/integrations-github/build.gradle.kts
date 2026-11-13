// SPDX-License-Identifier: AGPL-3.0-only
// Module integrations-github: the GitHub App (installation per organization, webhooks, created by the manifest flow
// when the environment names none), the head of a pull request read from GitHub for the gates, the `ostrom-gate`
// check run, each organization's directory (repositories and teams) read from its installation, and the rules
// analysis's reader of the repositories (files, CODEOWNERS, branch protection), read-only.
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
            // The organization an install start names (`?org=`): the orgs module's lookup by id or short name.
            implementation(project(":modules:orgs"))
            // The analysis of the rules reads the repositories through the installation: its RepositoryReader port.
            implementation(project(":modules:rules"))
            // Who a pull request's author is in Ostrom: the person whose account is linked to that GitHub user.
            implementation(project(":modules:identity"))
            // Whether cloud sessions are on (a new GitHub link starts only then): the runners module's configuration.
            implementation(project(":modules:runners"))
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
