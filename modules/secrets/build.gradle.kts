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
            implementation(libs.cryptography.openssl)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
