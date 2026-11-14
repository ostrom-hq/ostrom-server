// SPDX-License-Identifier: Apache-2.0
// Module automations: automations, schedules and waits.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // Seals the secrets of answered waits (waits.secrets.v1).
            implementation(project(":modules:secrets"))
            implementation(libs.kotlinx.datetime)
            implementation(libs.cryptography.core)
        }
        jvmMain.dependencies {
            implementation(libs.cryptography.jdk)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
