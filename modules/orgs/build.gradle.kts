// SPDX-License-Identifier: AGPL-3.0-only
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
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
