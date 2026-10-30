// SPDX-License-Identifier: AGPL-3.0-only
// Module sharing: sharing of personal subscriptions within a team.
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
