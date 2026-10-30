// Module sharing: personal subscription accounts (Claude Max, ChatGPT/Codex) pooled within an org, with their
// quota windows, per-account limits and token refresh. The gateway leases an account per request.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            api(project(":modules:secrets"))
            implementation(libs.ktor.client.core)
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
