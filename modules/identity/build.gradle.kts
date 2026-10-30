// SPDX-License-Identifier: AGPL-3.0-only
// Module identity: users, sign-in identities, browser sessions, API/CLI/agent tokens, the device login, the signed
// audit log, and the TokenAuthenticator the kernel resolves credentials with.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(libs.cryptography.core)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
