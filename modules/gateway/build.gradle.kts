// Module gateway: the inference gateway. Agents send Anthropic Messages, OpenAI Responses or Chat Completions to
// Ostrom; the gateway sends them on with the org's shared subscription accounts or its own provider keys (BYOK),
// streams the answer back untouched, meters usage and enforces budgets.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:secrets"))
            implementation(project(":modules:sharing"))
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
