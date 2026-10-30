// SPDX-License-Identifier: AGPL-3.0-only
// Module gates: gates: the human approvals a workflow step waits for.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(libs.cryptography.core)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
