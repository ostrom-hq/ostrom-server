// SPDX-License-Identifier: AGPL-3.0-only
// Module sessions: agent sessions: their lifecycle, transcripts and events.
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
