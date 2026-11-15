// SPDX-License-Identifier: Apache-2.0
// Module gates: gates: the human approvals a workflow step waits for.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:receipts"))
            // TENANT_ROLE_SQL: the tenant role its queries run as (migration 10), shared with every module.
            implementation(project(":modules:secrets"))
            implementation(libs.cryptography.core)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
