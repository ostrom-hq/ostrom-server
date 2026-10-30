// SPDX-License-Identifier: AGPL-3.0-only
// Module budgets: budgets, quotas and their enforcement on the gateway.
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
