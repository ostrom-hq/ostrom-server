// SPDX-License-Identifier: Apache-2.0
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
            // Outbound HTTPS to GitHub, Google, SSO providers and Resend: the app's CIO client, with the JDK's TLS.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.client.mock)
        }
    }
}
