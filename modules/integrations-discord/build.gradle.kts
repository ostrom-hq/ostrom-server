// SPDX-License-Identifier: Apache-2.0
// Module integrations-discord: the instance's Discord application (bot token, application id, public key, client
// secret and server id in the instance settings), each member's link to their Discord account (OAuth2 `identify`), the
// `discord` notification channel, and the yes requests' Reject button.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // The `discord` notification channel and the approvals' way to tell an approver (ApprovalChannel).
            implementation(project(":modules:notifications"))
            // The Reject button answers the request; the answers close the messages through the queue.
            implementation(project(":modules:gates"))
            // The member's login.
            implementation(project(":modules:identity"))
            // The members of an organization, their roles, and the organization's name.
            implementation(project(":modules:orgs"))
            // Ed25519 of Discord's interactions, SHA-256 of the link's state, its random bytes.
            implementation(libs.cryptography.core)
            // Outbound HTTPS to Discord's API: the app's CIO client.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(project(":modules:instance"))
            implementation(project(":modules:events"))
            implementation(project(":modules:secrets"))
            implementation(libs.ktor.client.mock)
        }
    }
}
