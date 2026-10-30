// SPDX-License-Identifier: AGPL-3.0-only
// Module receipts: signed receipts of what an agent did, byte-compatible with the prototype.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:instance"))
            // Canonical form, digests, signature field and verifier of ostrom-core: one implementation of format v1.
            api(libs.ostrom.receipts)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
