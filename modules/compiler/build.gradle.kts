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
            // The workflow and session package models of the contract.
            api(libs.ostrom.api)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
