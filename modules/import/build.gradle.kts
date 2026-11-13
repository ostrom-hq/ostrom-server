// SPDX-License-Identifier: Apache-2.0
// Module import: a one-time import of a TeamCode team (MCP servers, their secrets, assignments, tool policies and
// shared accounts), read by the CLI from TeamCode without writing to it, and applied here through the use cases of
// the modules that own the data.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:secrets"))
            implementation(project(":modules:mcp-relay"))
            implementation(project(":modules:sharing"))
            implementation(project(":modules:identity"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.client.mock)
        }
    }
}
