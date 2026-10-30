// SPDX-License-Identifier: AGPL-3.0-only
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
        nativeMain.dependencies {
            implementation(libs.cryptography.openssl3.prebuilt)
        }
        jvmMain.dependencies {
            implementation(libs.cryptography.jdk)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
