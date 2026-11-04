// SPDX-License-Identifier: AGPL-3.0-only
// Module compiler: compilation of workflows into signed packages.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:receipts"))
            // The compiler binds the gates module's GateNodeResolver and resolves callers with its GateActorResolver.
            implementation(project(":modules:gates"))
            // The workflow and session package models of the contract.
            api(libs.ostrom.api)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            // The receipts module signs with the instance's keys.
            implementation(project(":modules:instance"))
        }
    }
}
