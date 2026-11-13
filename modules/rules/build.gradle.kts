// SPDX-License-Identifier: Apache-2.0
// Module rules: the organization's rules, its signed decisions and its gate policy (self-approval), which the gates
// module reads.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // The member a console call acts for (CallerResolver, on the kernel's PrincipalOrgResolver).
            implementation(project(":modules:secrets"))
            // The self-approval policy is the gates module's GatePolicies port; who decides is its GateActorResolver.
            implementation(project(":modules:gates"))
            // Every decision signs a receipt.
            implementation(project(":modules:receipts"))
            // A review date is checked against the calendar.
            implementation(libs.kotlinx.datetime)
            // The rules are the compiler's sources: the harness carries them, their gates enter the catalogue.
            implementation(project(":modules:compiler"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            // The receipts module signs with the instance's keys.
            implementation(project(":modules:instance"))
            // The audit log the rules write to (`rule.*`), signed in the organization's chain.
            implementation(project(":modules:identity"))
        }
    }
}
