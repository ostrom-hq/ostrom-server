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
        }
        // The JDK provides Ed25519 there.
        jvmMain.dependencies {
            implementation(libs.cryptography.jdk)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
