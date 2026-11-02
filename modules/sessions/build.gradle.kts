// SPDX-License-Identifier: AGPL-3.0-only
// Module sessions: the segments of the coding agent sessions, their sign of life and their signed receipts.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:gates"))
            implementation(project(":modules:receipts"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(project(":modules:instance"))
        }
    }
}
