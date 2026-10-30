// SPDX-License-Identifier: AGPL-3.0-only
rootProject.name = "ostrom-server"

// No TYPESAFE_PROJECT_ACCESSORS (CONVENTIONS section 9): the module `artifacts` would generate an accessor that
// clashes with ProjectDependency.getArtifacts() and breaks the build. Modules depend on project(":path").

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // ostrom-core is published to GitHub Packages (private): token from GITHUB_ACTOR / GITHUB_TOKEN in CI,
        // or gpr.user / gpr.key in ~/.gradle/gradle.properties locally. Only dev.ostrom is looked up there.
        maven("https://maven.pkg.github.com/ostrom-hq/ostrom-core") {
            name = "ostromCore"
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
            content { includeGroupAndSubgroups("dev.ostrom") }
        }
    }
}

// Local development against a checkout of ostrom-core next to this one: -Postrom.core.local=true.
if (providers.gradleProperty("ostrom.core.local").orNull == "true") {
    includeBuild("../ostrom-core")
}

include(":kernel", ":testing", ":app", ":runner")

// Every directory of modules/ with a build file is a functional module (:modules:<name>), discovered here so that
// adding a module never edits this file.
file("modules").listFiles()
    ?.filter { it.isDirectory && it.resolve("build.gradle.kts").exists() }
    ?.sortedBy { it.name }
    ?.forEach { include(":modules:${it.name}") }
