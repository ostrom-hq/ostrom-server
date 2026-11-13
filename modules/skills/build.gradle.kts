// SPDX-License-Identifier: Apache-2.0
// Module skills: the organization's skill catalog (TeamCode's), its members' personal skills and their delivery.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
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
