// SPDX-License-Identifier: AGPL-3.0-only
// Module notifications: the phones a person registers for push notifications, per organization and per phone.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // NOTIFICATIONS_WRITE_SCOPE: a browser session and the phone app's token hold it, a personal token never.
            implementation(project(":modules:identity"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(project(":modules:instance"))
            implementation(project(":modules:orgs"))
        }
    }
}
