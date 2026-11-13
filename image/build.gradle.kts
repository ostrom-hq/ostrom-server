// SPDX-License-Identifier: Apache-2.0
// The server image (docs/decisions/0001): the JVM application of :app, packaged by the Ktor Gradle plugin's
// `ktor { docker { } }` and jib, as TeamCode's app/build.gradle.kts. No Dockerfile.
//   ./gradlew :image:publishImageToLocalRegistry -PimageTags=ci -PimagePlatforms=amd64   → ostrom-server:ci in Docker
//   ./gradlew :image:publishImage -PimageTags=latest,sha-<commit>                        → ostromhq/ostrom-server
// The image runs /usr/local/bin/ostrom-server (src/main/jib), so `docker run … migrate` and the chart's
// `/usr/local/bin/ostrom-server migrate` keep working.
import io.ktor.plugin.features.DockerImageRegistry
import io.ktor.plugin.features.DockerPortMapping
import io.ktor.plugin.features.DockerPortMappingProtocol

plugins {
    id("ostrom.jvm-image")
}

application {
    mainClass.set("run.ostrom.server.app.MainKt")
    // `./gradlew :image:installDist` → image/build/install/ostrom-server/bin/ostrom-server, for a local run.
    applicationName = "ostrom-server"
}

dependencies {
    implementation(project(":app"))
}

// Multi-platform images can only be pushed to a registry; local builds pass a single platform.
val imagePlatforms = providers.gradleProperty("imagePlatforms").orElse("amd64,arm64").get()
    .split(',').map(String::trim).filter(String::isNotEmpty)
// Image tags come from the workflow as a comma-separated list; the first one names the image.
val imageTags = providers.gradleProperty("imageTags").orElse("latest").get()
    .split(',').map(String::trim).filter(String::isNotEmpty)
// The commit the image is built from (the image workflow passes it), for the OCI revision label.
val imageRevision = providers.gradleProperty("imageRevision").orElse("").get()

ktor {
    docker {
        jreVersion.set(JavaVersion.VERSION_21)
        // The JRE the Ktor plugin would pick, pinned to its Ubuntu release.
        customBaseImage.set("eclipse-temurin:21-jre-noble")
        localImageName.set("ostrom-server")
        imageTag.set(imageTags.first())
        portMappings.set(listOf(DockerPortMapping(8080, 8080, DockerPortMappingProtocol.TCP)))
        externalRegistry.set(
            DockerImageRegistry.dockerHub(
                appName = provider { "ostrom-server" },
                username = providers.environmentVariable("DOCKER_HUB_USERNAME").orElse("ostromhq"),
                password = providers.environmentVariable("DOCKER_HUB_PASSWORD"),
            ),
        )
    }
}

jib {
    from {
        platforms {
            imagePlatforms.forEach { arch ->
                platform {
                    architecture = arch
                    os = "linux"
                }
            }
        }
    }
    to { tags = imageTags.drop(1).toSet() }
    extraDirectories {
        paths { path { setFrom(file("src/main/jib")) } }
        // The data directory is root's (jib sets no owner): every user may write the instance key there.
        permissions.set(mapOf("/usr/local/bin/ostrom-server" to "755", "/var/lib/ostrom" to "777"))
    }
    container {
        // The chart's server.runAsUser and the old distroless image's user.
        user = "65532:65532"
        workingDirectory = "/var/lib/ostrom"
        entrypoint = listOf("/usr/local/bin/ostrom-server")
        // Written to /app/jib-main-class-file, read by the launcher: with a custom entrypoint jib does not take it
        // from `application` and would write `could-not-infer-a-main-class`.
        mainClass = "run.ostrom.server.app.MainKt"
        environment = mapOf(
            "OSTROM_SIGNING_KEY_FILE" to "/var/lib/ostrom/instance-key.pem",
            // TeamCode's options (helm values.yaml:110): the heap follows the container's limit, and an
            // OutOfMemoryError ends the process instead of leaving it half-broken. The chart sets its own.
            "JAVA_TOOL_OPTIONS" to "-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError",
        )
        labels.set(
            buildMap {
                put("org.opencontainers.image.title", "ostrom-server")
                put("org.opencontainers.image.source", "https://github.com/ostrom-hq/ostrom-server")
                put("org.opencontainers.image.licenses", "Apache-2.0")
                put("org.opencontainers.image.version", version.toString())
                if (imageRevision.isNotEmpty()) put("org.opencontainers.image.revision", imageRevision)
            },
        )
    }
}
