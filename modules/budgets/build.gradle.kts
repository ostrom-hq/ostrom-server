// SPDX-License-Identifier: AGPL-3.0-only
// Module budgets: monthly spending limits per org and per member, and the guard the gateway asks before each model
// call. Reads the spend from the usage ledger.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:secrets"))
            api(project(":modules:usage"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
