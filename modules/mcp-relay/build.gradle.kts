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
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.openssl)
        }
        linuxMain.dependencies {
            implementation(libs.ktor.client.curl)
        }
        macosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.client.mock)
        }
    }
}
