// SPDX-License-Identifier: AGPL-3.0-only
// Module runners: cloud runners and devices that execute agents.
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
