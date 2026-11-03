// SPDX-License-Identifier: AGPL-3.0-only
// Module notifications: the phones a person registers for push notifications, per organization and per phone, and the
// push and e-mail that tell an approver a gate waits for their yes.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // NOTIFICATIONS_WRITE_SCOPE: a browser session and the phone app's token hold it, a personal token never.
            // The approvers' people, preferences and credentials, and the mail provider.
            implementation(project(":modules:identity"))
            // The members of an organization, and the organizations of a person.
            implementation(project(":modules:orgs"))
            // The gate channel the gates module tells of each request.
            implementation(project(":modules:gates"))
            // Outbound HTTPS to Expo's push service: Curl on Linux, Darwin on macOS (CIO has no TLS on Kotlin/Native).
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(project(":modules:instance"))
            implementation(libs.ktor.client.mock)
        }
    }
}
