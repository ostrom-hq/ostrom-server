// Module gates: gates: the human approvals a workflow step waits for.
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
