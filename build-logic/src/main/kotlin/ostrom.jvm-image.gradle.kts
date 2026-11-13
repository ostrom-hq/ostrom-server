// SPDX-License-Identifier: Apache-2.0
// The server's image project (image/): a JVM application with the Ktor Gradle plugin, whose `ktor { docker { } }` and
// jib build and publish the image, as TeamCode's app/build.gradle.kts (docs/decisions/0001). The Ktor plugin cannot
// build an image from a Kotlin Multiplatform project (KTOR-8464): this JVM project depends on :app and packages it.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
    id("io.ktor.plugin")
}

kotlin { jvmToolchain(21) }
