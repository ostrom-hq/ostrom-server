// SPDX-License-Identifier: AGPL-3.0-only
// Module todos: a session's todo, its verified end conditions and the Stop hook's questions (TeamCode docs/todos.md).
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
        }
    }
}
