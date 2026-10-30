// Convention for every Gradle module of ostrom-server: Kotlin/Native only (no JVM), the three server targets,
// kotlinx.serialization, coroutines and Koin. A module's build file applies this plugin and adds its own dependencies.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

kotlin {
    linuxX64()
    linuxArm64()
    macosArm64()

    compilerOptions {
        optIn.addAll(
            "kotlin.uuid.ExperimentalUuidApi",
            "kotlin.time.ExperimentalTime",
            "kotlinx.cinterop.ExperimentalForeignApi",
        )
    }

    sourceSets {
        commonMain.dependencies {
            implementation(lib("coroutines-core"))
            implementation(lib("serialization-json"))
            implementation(lib("koin-core"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(lib("coroutines-test"))
        }
    }
}

// Every test is reported as it runs, with its output: a native test that hangs is then visible in the CI log.
tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events("started", "passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
