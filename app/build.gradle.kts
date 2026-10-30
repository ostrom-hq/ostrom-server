// The server binary: reads the configuration, opens the database, applies every module's migrations, wires Koin
// and serves HTTP on Ktor's CIO engine. Depends on every module of modules/. The entry point is native only
// (nativeMain); the module list (commonMain) is also tested on the JVM for coverage.
plugins {
    id("ostrom.native-module")
}

kotlin {
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
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
