// SPDX-License-Identifier: AGPL-3.0-only
// Module launches: `ostrom <agent>` asks for a launch and gets an agent token scoped to its sessions (audit A12/A13).
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // The callers of the gates and sessions routes, which this module resolves (GateActorResolver).
            implementation(project(":modules:gates"))
            // The people behind a credential, and the agent token of a launch.
            implementation(project(":modules:identity"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            // The launch end to end: a real person, organization and token, then a session of the launch.
            implementation(project(":modules:orgs"))
            implementation(project(":modules:instance"))
            implementation(project(":modules:receipts"))
            implementation(project(":modules:sessions"))
        }
    }
}
