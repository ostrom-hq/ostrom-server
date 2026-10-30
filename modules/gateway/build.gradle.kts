// SPDX-License-Identifier: AGPL-3.0-only
// Module gateway: the inference gateway. Agents send Anthropic Messages, OpenAI Responses or Chat Completions to
// Ostrom; the gateway sends them on with the org's shared subscription accounts or its own provider keys (BYOK),
// streams the answer back untouched, meters usage and enforces budgets.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:secrets"))
            implementation(project(":modules:sharing"))
            implementation(libs.ktor.client.core)
            // The engine of each platform: Curl on Linux, Darwin on macOS, CIO on the test-only JVM target.
            implementation(libs.ktor.client.engine.defaults)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.client.mock)
        }
    }
}
