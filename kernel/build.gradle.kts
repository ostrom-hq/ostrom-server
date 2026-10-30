// SPDX-License-Identifier: AGPL-3.0-only
// The shared kernel every functional module builds on: configuration, logs, database and migrations, uniform
// HTTP errors, token authentication, the instance signing key, the ServerModule contract and observability (traces,
// metrics and logs through ostrom-core's telemetry module).
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.server.core)
            api(libs.ktor.server.content.negotiation)
            api(libs.ktor.server.status.pages)
            api(libs.ktor.server.resources)
            api(libs.ktor.server.auth)
            api(libs.ktor.server.call.id)
            api(libs.ktor.serialization.json)
            api(libs.koin.core)
            api(libs.sqlx4k.postgres)
            api(libs.kotlinx.io)
            api(libs.ostrom.telemetry)
            implementation(libs.cryptography.core)
        }
        nativeMain.dependencies {
            implementation(libs.cryptography.openssl3.prebuilt)
        }
        // The JVM target only runs the tests and Kover (never shipped): the JDK provides Ed25519 there.
        jvmMain.dependencies {
            implementation(libs.cryptography.jdk)
            // Loading a key file needs its public key, which the JDK alone cannot derive from an Ed25519 private key.
            runtimeOnly(libs.bouncycastle.bcprov)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.server.test.host)
            implementation(libs.ktor.client.content.negotiation)
        }
    }
}
