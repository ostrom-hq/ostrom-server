// SPDX-License-Identifier: Apache-2.0
// Module budgets: monthly spending limits per org and per member, and the guard the gateway asks before each model
// call. Reads the spend and holds the calls in flight on the usage ledger.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:secrets"))
            api(project(":modules:usage"))
            // The gateway's SpendGuard port, bound here to the limits and the ledger's holds.
            implementation(project(":modules:gateway"))
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
