// SPDX-License-Identifier: AGPL-3.0-only
// Module gateway: the inference gateway: providers, routing and usage.
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
