// SPDX-License-Identifier: Apache-2.0
// Module billing: Stripe subscriptions per active person, plan limits, cloud agent usage and alerts.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // Darwin on macOS, Curl on Linux, CIO on the test-only JVM target.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
            implementation(libs.cryptography.core)
            implementation(libs.kotlinx.datetime)
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
            implementation(libs.ktor.server.test.host)
        }
    }
}
