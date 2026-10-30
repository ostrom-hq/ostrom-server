// Module billing: Stripe subscriptions per active person, plan limits, cloud agent usage and alerts.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
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
            implementation(libs.ktor.server.test.host)
        }
    }
}
