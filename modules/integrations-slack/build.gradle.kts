// SPDX-License-Identifier: AGPL-3.0-only
// Module integrations-slack: the Slack integration: app installation, events, messages.
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
