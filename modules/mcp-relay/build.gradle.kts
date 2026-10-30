// SPDX-License-Identifier: AGPL-3.0-only
// Module mcp-relay: the MCP relay: servers, tools and the access each member has.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
