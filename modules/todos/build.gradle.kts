// SPDX-License-Identifier: AGPL-3.0-only
// Module todos: a session's todo, its verified end conditions and the Stop hook's questions (TeamCode docs/todos.md).
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // The session a todo belongs to, its segments and its person.
            implementation(project(":modules:sessions"))
            // The waits a drop request and a stall ask the owner, and the wait_answered check.
            implementation(project(":modules:automations"))
            // The artifact_published check.
            implementation(project(":modules:artifacts"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(project(":modules:gates"))
            implementation(project(":modules:receipts"))
        }
    }
}
