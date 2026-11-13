// SPDX-License-Identifier: Apache-2.0
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
        // or gpr.user / gpr.key in ~/.gradle/gradle.properties locally. Only run.ostrom is looked up there.
        maven("https://maven.pkg.github.com/ostrom-hq/ostrom-core") {
            name = "ostromCore"
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
            content { includeGroupAndSubgroups("run.ostrom") }
        }
    }
}

// ostrom-core from a checkout next to this one instead of GitHub Packages: -Postrom.core.local=true (or the same line
// in gradle.properties). Local development uses it, and so does CI, which cannot read the packages of another
// repository: it checks ostrom-core out with its read-only deploy key at the commit of gradle/ostrom-core.ref (JOURNAL
// 05:50). Each published artifact is replaced by the project that publishes it.
if (providers.gradleProperty("ostrom.core.local").orNull == "true") {
    includeBuild("../ostrom-core") {
        dependencySubstitution {
            for (module in listOf("api", "receipts", "telemetry")) {
                substitute(module("run.ostrom:ostrom-$module")).using(project(":$module"))
            }
        }
    }
}

include(":kernel", ":testing", ":app", ":runner", ":mcp-host")

// Every directory of modules/ with a build file is a functional module (:modules:<name>), discovered here so that
// adding a module never edits this file.
file("modules").listFiles()
    ?.filter { it.isDirectory && it.resolve("build.gradle.kts").exists() }
    ?.sortedBy { it.name }
    ?.forEach { include(":modules:${it.name}") }

// Every directory of ee/modules/ with a build file is an enterprise module (:ee:modules:<name>, licensed under
// ee/LICENSE). Discovered the same way, so that deleting ee/ gives the Apache-2.0 build with nothing else to edit; the app
// registers what it finds (app/build.gradle.kts, ee/README.md).
file("ee/modules").listFiles()
    ?.filter { it.isDirectory && it.resolve("build.gradle.kts").exists() }
    ?.sortedBy { it.name }
    ?.forEach { include(":ee:modules:${it.name}") }
