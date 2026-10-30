// SPDX-License-Identifier: AGPL-3.0-only
// Test helpers shared by every module's tests: a throwaway PostgreSQL database per test and a test server with
// the kernel and the modules under test. Only ever a test dependency.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kernel"))
            api(libs.ktor.server.test.host)
            api(libs.ktor.client.content.negotiation)
            api(libs.coroutines.test)
            api(kotlin("test"))
        }
    }
}
