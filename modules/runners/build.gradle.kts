// SPDX-License-Identifier: Apache-2.0
// Module runners: cloud sessions (one pod per session on Kubernetes, or a local Docker container), their
// reconciler, the runner channel and the terminal relay.
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // The port automations start their runs through ([AutomationLauncher]), bound here.
            implementation(project(":modules:automations"))
            // The plans and the hours counter of billing ([GetPlanLimitsUseCase], [RecordAgentUsageUseCase]).
            implementation(project(":modules:billing"))
            // The console's cloud runtime: the runner image a session's init container copies `ostrom` from.
            implementation(project(":modules:cloud"))
            // The members' environment values the runner hands the agent ([ResolveEnvironmentUseCase]).
            implementation(project(":modules:secrets"))
            implementation(libs.cryptography.core)
            implementation(libs.ktor.client.core)
            // The hops of a terminal to the replica holding its runner (TerminalForwarder).
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.server.websockets)
        }
        // The JDK provides SHA-256 and CIO the client.
        jvmMain.dependencies {
            implementation(libs.cryptography.jdk)
            implementation(libs.ktor.client.cio)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            implementation(libs.ktor.client.mock)
        }
    }
}
