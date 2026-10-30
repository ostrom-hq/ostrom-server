// Module gates: gates: the human approvals a workflow step waits for.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:receipts"))
            implementation(libs.cryptography.core)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
