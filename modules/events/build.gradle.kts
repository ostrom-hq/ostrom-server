// SPDX-License-Identifier: Apache-2.0
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
            // The webhooks' signatures and secrets.
            implementation(libs.cryptography.core)
        }
        nativeMain.dependencies {
            implementation(libs.cryptography.openssl3.prebuilt)
        }
        jvmMain.dependencies {
            implementation(libs.cryptography.jdk)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.client.mock)
        }
    }
}
