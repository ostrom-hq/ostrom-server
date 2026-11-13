// SPDX-License-Identifier: Apache-2.0
// Module artifacts: published artifacts (pages and files) and their sharing.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // The cipher that seals the bucket's secret access key (the instance's secrets key).
            implementation(project(":modules:secrets"))
            implementation(libs.cryptography.core)
            // The organization's S3-compatible bucket: Curl on Linux, Darwin on macOS (CIO has no TLS on native).
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
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
