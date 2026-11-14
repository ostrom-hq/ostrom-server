// SPDX-License-Identifier: Apache-2.0
// Module notifications: the phones a person registers for push notifications, per organization and per phone, and the
// push and e-mail that tell an approver a gate waits for their yes. FCM pushes are encrypted for the phone's key.
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
            // The secrets key, which seals the instance's signing key at the push relay.
            implementation(project(":modules:secrets"))
            // Outbound HTTPS to FCM and Google's token endpoint: the app's CIO client.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
            // The pushes' end-to-end encryption (ECDH P-256, HKDF, AES-GCM) and the service account's RS256 assertion.
            implementation(libs.cryptography.core)
        }
        // The JDK provides them there.
        jvmMain.dependencies {
            implementation(libs.cryptography.jdk)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(project(":modules:instance"))
            // The gates whose requests the approvals' end-to-end tests ask, and their summaries.
            implementation(project(":modules:gates"))
            // The event journal the router reads, and the cipher the events module needs.
            implementation(project(":modules:events"))
            implementation(project(":modules:secrets"))
            implementation(libs.ktor.client.mock)
        }
    }
}
