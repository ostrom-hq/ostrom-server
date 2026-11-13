// SPDX-License-Identifier: AGPL-3.0-only
// Module events: the organization's event feed (one gap-free sequence per organization in PostgreSQL, read by every
// replica, streamed as server-sent events) and its outgoing webhooks.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // TENANT_ROLE_SQL and tenantTableSql: the grants of the tenant role its tables share with every module.
            implementation(project(":modules:secrets"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
