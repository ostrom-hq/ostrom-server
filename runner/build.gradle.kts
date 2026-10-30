// The runner: the process of a cloud session's pod. It opens a PTY running the agent (after cloning the
// repository), relays it to the server's terminal WebSocket, and heartbeats the execution. Native, no JVM.
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
            entryPoint = "dev.ostrom.runner.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
