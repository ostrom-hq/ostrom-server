// Module secrets: the org's encrypted secrets (AES-256-GCM under the instance's secrets key), injected server side
// into MCP servers and providers, never returned to a client. Also the caller resolution every RELAY module shares.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(libs.cryptography.core)
        }
        nativeMain.dependencies {
            implementation(libs.cryptography.openssl3.prebuilt)
        }
        // The JVM target only runs the tests and Kover (never shipped): the JDK provides AES-GCM there.
        jvmMain.dependencies {
            implementation(libs.cryptography.jdk)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
