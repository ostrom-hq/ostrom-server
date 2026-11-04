// SPDX-License-Identifier: AGPL-3.0-only
// Module workflow-runs: what the compiled workflows did in a session (checks run, notifications sent), its signed
// workflow receipt, and the receipt bundle `ostrom verify` reads offline.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:gates"))
            implementation(project(":modules:sessions"))
            implementation(project(":modules:receipts"))
            implementation(project(":modules:compiler"))
            implementation(project(":modules:launches"))
            implementation(project(":modules:runners"))
            implementation(project(":modules:identity"))
            implementation(project(":modules:automations"))
            implementation(libs.ostrom.api)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(project(":modules:instance"))
            implementation(project(":modules:orgs"))
        }
    }
}
