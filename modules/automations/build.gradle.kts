// SPDX-License-Identifier: Apache-2.0
// Module automations: automations, schedules and waits.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
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
