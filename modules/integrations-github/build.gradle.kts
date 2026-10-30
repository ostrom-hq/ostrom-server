// SPDX-License-Identifier: AGPL-3.0-only
// Module integrations-github: the GitHub integration: app installation, webhooks, pull requests.
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
