// The shared kernel every functional module builds on: configuration, logs, database and migrations, uniform
// HTTP errors, token authentication, the instance signing key and the ServerModule contract.
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
            api(libs.sqlx4k.postgres)
            api(libs.kotlinx.io)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.openssl)
        }
        commonTest.dependencies {
            implementation(libs.ktor.server.test.host)
        }
    }
}
