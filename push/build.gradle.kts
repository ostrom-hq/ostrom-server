// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/build.gradle.kts; see NOTICE.
// The Ostrom Push relay (push/README.md): a small Kotlin/Native server, run on Cloud Run under the Firebase project of
// the published apps. It depends on ostrom-core's `api` only (the `PushRelay` wire models): no database, no kernel, no
// encryption key. The JVM target runs the tests in ordinary CI; linuxX64 is the binary that is deployed.
plugins {
    id("ostrom.native-module")
}

kotlin {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = "ostrom-push"
            entryPoint = "run.ostrom.server.push.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ostrom.api)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.client.engine.defaults)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.flareon.core)
            implementation(libs.flareon.messaging)
            implementation(libs.cryptography.core)
        }
        jvmMain.dependencies { implementation(libs.cryptography.jdk) }
        nativeMain.dependencies {
            implementation(libs.ktor.server.cio)
            implementation(libs.cryptography.openssl3.prebuilt)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.server.test.host)
        }
    }
}

// flareon is built with cryptography 0.4.0; one version of its modules (0.6.0, which has Ed25519) for the whole binary.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "dev.whyoleg.cryptography") useVersion(libs.versions.cryptography.get())
    }
}
