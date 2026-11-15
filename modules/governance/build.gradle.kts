// SPDX-License-Identifier: Apache-2.0
// Module governance: a governance write made with a person's token waits until an owner or an admin confirms it on
// the console with a fresh sign-in (audit CLI H1, decision of 02/11/2026).
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // The signed audit log (each step of a confirmation) and the people's names.
            implementation(project(":modules:identity"))
            // TENANT_ROLE_SQL: the tenant role its queries run as (migration 4), shared with every module.
            implementation(project(":modules:secrets"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
