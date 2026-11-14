// SPDX-License-Identifier: Apache-2.0
// Module memory: the agent memory, team and personal entries read on demand (TeamCode docs/memory.md).
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // Who a request acts for: a person's member and role, an agent token's member (CallerResolver).
            implementation(project(":modules:secrets"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
