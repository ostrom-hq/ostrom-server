// SPDX-License-Identifier: AGPL-3.0-only
// Module orgs: organizations, teams, memberships and roles.
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
