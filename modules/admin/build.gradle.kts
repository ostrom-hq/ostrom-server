// SPDX-License-Identifier: AGPL-3.0-only
// The instance's administration: its administrators and its settings sections, stored in PostgreSQL with their
// secrets sealed by the secrets key (TeamCode's dashboard settings), instead of the environment or the Helm chart.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:secrets"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
