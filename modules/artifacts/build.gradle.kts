// SPDX-License-Identifier: AGPL-3.0-only
// Module artifacts: published artifacts (pages and files) and their sharing.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(libs.cryptography.core)
            // The organization's S3-compatible bucket, reached through the outbound client the module is given.
            implementation(libs.ktor.client.core)
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
