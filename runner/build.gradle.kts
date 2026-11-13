// SPDX-License-Identifier: Apache-2.0
// The runner: the process of a cloud session's pod. Its agent host, in the agent's own container, opens the PTY
// running the agent (after cloning the repository); the runner relays it to the server's terminal WebSocket and
// heartbeats the execution. Native, no JVM.
plugins {
    id("ostrom.native-module")
}

kotlin {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("pty") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/pty.def"))
        }
        binaries.executable {
            baseName = "ostrom-runner"
            entryPoint = "run.ostrom.runner.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.network)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
        // The terminal link against a real WebSocket server: Ktor's test host has WebSockets on the JVM only.
        jvmTest.dependencies {
            implementation(libs.ktor.server.test.host)
            implementation(libs.ktor.server.websockets)
        }
    }
}
