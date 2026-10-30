// SPDX-License-Identifier: AGPL-3.0-only
// Module receipts: signed receipts of what an agent did, byte-compatible with the prototype.
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
