// SPDX-License-Identifier: Apache-2.0
// Module orgs: organizations, members and roles, invitations, the free-plan guard, and the PrincipalOrgResolver.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // People, the audit log, secrets, the rate limiter and mail come from the identity module's interfaces.
            implementation(project(":modules:identity"))
            // The time zone database, to accept only known zones.
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
