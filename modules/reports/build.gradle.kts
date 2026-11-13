// SPDX-License-Identifier: Apache-2.0
// Module reports: the console's and the app's read models across modules — the spending report (the usage ledger by
// agent, repository and person, against the budget) and the activity feed (sessions, gates, workflow decisions).
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:budgets"))
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(project(":modules:instance"))
            implementation(project(":modules:secrets"))
            implementation(project(":modules:usage"))
            implementation(project(":modules:identity"))
            implementation(project(":modules:orgs"))
            implementation(project(":modules:receipts"))
            implementation(project(":modules:gates"))
            implementation(project(":modules:sessions"))
            implementation(project(":modules:compiler"))
        }
    }
}
