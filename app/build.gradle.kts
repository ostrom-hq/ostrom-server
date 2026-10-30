// SPDX-License-Identifier: AGPL-3.0-only
// The server binary: `ostrom-server` serves HTTP on Ktor's CIO engine (role checked, migrations verified, graceful
// stop on SIGTERM), `ostrom-server migrate` applies every module's migrations with the owner's credentials, and
// `ostrom-server seed-test-account` creates a QA account. Depends on every module of modules/. The entry point is
// native only (nativeMain); the module list and the commands (commonMain) are also tested on the JVM for coverage.
plugins {
    id("ostrom.native-module")
}

kotlin {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        // SIGTERM and SIGINT through a pipe (src/nativeInterop/cinterop/signals.def), for the graceful stop.
        compilations.getByName("main").cinterops.create("signals")
        binaries.executable {
            baseName = "ostrom-server"
            entryPoint = "dev.ostrom.server.app.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            rootProject.subprojects
                .filter { it.path.startsWith(":modules:") }
                .forEach { implementation(project(it.path)) }
        }
        nativeMain.dependencies {
            implementation(libs.ktor.server.cio)
        }
        commonTest.dependencies {
            implementation(project(":testing"))
        }
    }
}
