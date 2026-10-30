// SPDX-License-Identifier: AGPL-3.0-only
// Module usage: the central ledger of everything the org's members spend (model calls, MCP calls, skills, hooks,
// sessions), its live feed, and the spend the budgets and the gateway read.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            api(project(":modules:secrets"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
