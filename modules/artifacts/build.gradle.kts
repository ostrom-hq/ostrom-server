// SPDX-License-Identifier: AGPL-3.0-only
// Module artifacts: published artifacts (pages and files) and their sharing.
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
