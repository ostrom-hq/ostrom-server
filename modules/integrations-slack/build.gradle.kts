// SPDX-License-Identifier: Apache-2.0
// Module integrations-slack: the instance's Slack app (bot token and signing secret in the instance settings), each
// member's link to their Slack account, the `slack` notification channel, and the yes requests' Reject button.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // The `slack` notification channel and the approvals' way to tell an approver (ApprovalChannel).
            implementation(project(":modules:notifications"))
            // The Reject button answers the request; the answers close the messages through the queue.
            implementation(project(":modules:gates"))
            // The member's proven e-mail address, looked up in Slack.
            implementation(project(":modules:identity"))
            // The members of an organization, their roles, and the organization's name.
            implementation(project(":modules:orgs"))
            // HMAC-SHA256 of Slack's request signature (the JDK provider, linked by the kernel).
            implementation(libs.cryptography.core)
            // Outbound HTTPS to Slack's Web API: the app's CIO client.
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
