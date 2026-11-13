// SPDX-License-Identifier: Apache-2.0
// Module sharing: personal subscription accounts (Claude Max, ChatGPT/Codex) pooled within an org, with their
// quota windows, per-account limits and token refresh. The gateway leases an account per request.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            api(project(":modules:secrets"))
            implementation(libs.ktor.client.core)
            // The engine of each platform: Curl on Linux, Darwin on macOS, CIO on the test-only JVM target.
            implementation(libs.ktor.client.engine.defaults)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.client.mock)
            // The console's enrollment end to end: real people, organizations and members name the accounts' owners.
            implementation(project(":modules:identity"))
            implementation(project(":modules:orgs"))
        }
    }
}
