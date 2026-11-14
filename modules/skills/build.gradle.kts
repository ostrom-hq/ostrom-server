// SPDX-License-Identifier: Apache-2.0
// Module skills: the organization's skill catalog (TeamCode's), its members' personal skills and their delivery.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // The cloud sessions a runner call names, for the runner's delivery (TC-SKL-11).
            implementation(project(":modules:runners"))
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
