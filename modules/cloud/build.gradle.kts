// SPDX-License-Identifier: Apache-2.0
// TeamCode's cloud administration: the instance's cloud runtime and Kubernetes clusters, and the organizations'
// versioned cloud profiles, stored in PostgreSQL with the cluster credentials sealed by the secrets key.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            implementation(project(":modules:secrets"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
