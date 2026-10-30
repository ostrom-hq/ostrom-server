// SPDX-License-Identifier: AGPL-3.0-only
// Module secrets: encrypted secrets and their injection into runs.
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
