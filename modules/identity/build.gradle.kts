// SPDX-License-Identifier: AGPL-3.0-only
// Module identity: users, sign-in identities, browser sessions, API/CLI/agent tokens, the device login, the signed
// audit log, and the TokenAuthenticator the kernel resolves credentials with.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(libs.cryptography.core)
            // Outbound HTTPS to GitHub, Google, SSO providers and Resend: Curl on Linux, Darwin on macOS (CIO has no
            // TLS on Kotlin/Native), as in ostrom-cli.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.client.mock)
        }
    }
}
