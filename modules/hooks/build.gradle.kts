// SPDX-License-Identifier: Apache-2.0
// Module hooks: the organization's team hooks (TeamCode's), approved by an administrator's pin and delivered to members.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // The artifact rules, the callers and the access policy the skill catalog already holds.
            implementation(project(":modules:skills"))
            // The cloud sessions a runner call names, for the runner's delivery (TC-HOK-9).
            implementation(project(":modules:runners"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
