// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts.infrastructure

import dev.ostrom.server.artifacts.domain.ArtifactLocations
import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.InvalidConfigException

/** Reads [ArtifactLocations] from `OSTROM_ARTIFACTS_VIEWER_URL` and `OSTROM_ARTIFACTS_CONTENT_ORIGIN`. */
internal object ArtifactsConfig {
    /** The console's artifact page when none is configured (the web console's route). */
    const val DEFAULT_VIEWER_URL = "/artifacts"

    private val ORIGIN = Regex("https?://[A-Za-z0-9.-]+(:[0-9]{1,5})?")

    /**
     * Reads the locations.
     *
     * @param source The configuration.
     *
     * @return The locations.
     *
     * @throws InvalidConfigException When the content origin is not a bare http(s) origin, or shares the viewer's
     *   host (pages would then run next to the console's session).
     */
    fun from(source: ConfigSource): ArtifactLocations {
        val viewer = source["OSTROM_ARTIFACTS_VIEWER_URL"]?.trimEnd('/')?.takeIf { it.isNotBlank() }
            ?: DEFAULT_VIEWER_URL
        val origin = source["OSTROM_ARTIFACTS_CONTENT_ORIGIN"]?.trimEnd('/')?.takeIf { it.isNotBlank() }
        if (origin != null && (!ORIGIN.matches(origin) || viewer.startsWith("$origin/"))) {
            throw InvalidConfigException(
                "OSTROM_ARTIFACTS_CONTENT_ORIGIN must be an http(s) origin of its own (https://artifacts.example.com).",
            )
        }
        return ArtifactLocations(viewer, origin)
    }

    /**
     * The host of the content origin, which content routes answer on and nothing else.
     *
     * @return `artifacts.example.com`, or null without an origin.
     */
    fun contentHost(locations: ArtifactLocations): String? =
        locations.contentOrigin?.substringAfter("://")?.substringBefore(':')
}
