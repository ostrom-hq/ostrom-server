// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
// Enterprise module license: verifies the Ed25519-signed license key offline and binds billing's LicenseProvider.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:billing"))
            implementation(project(":modules:instance"))
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.openssl)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
