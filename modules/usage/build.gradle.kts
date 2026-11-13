// SPDX-License-Identifier: Apache-2.0
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
            // The ledger takes the gateway's model calls and measures a session's cost (their ports, bound here).
            implementation(project(":modules:gateway"))
            implementation(project(":modules:sessions"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
