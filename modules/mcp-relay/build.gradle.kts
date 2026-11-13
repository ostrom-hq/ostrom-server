// SPDX-License-Identifier: Apache-2.0
// Module mcp-relay: the org's MCP servers relayed by the server (HTTPS upstreams, and stdio commands run by the MCP
// host), the tools each member is assigned, argument policies, and the journal of every call.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:secrets"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.engine.defaults)
            implementation(libs.cryptography.core)
        }
        nativeMain.dependencies {
            implementation(libs.cryptography.openssl3.prebuilt)
        }
        // The JVM target only runs the tests and Kover (never shipped): the JDK provides SHA-256 there.
        jvmMain.dependencies {
            implementation(libs.cryptography.jdk)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.client.mock)
        }
    }
}
