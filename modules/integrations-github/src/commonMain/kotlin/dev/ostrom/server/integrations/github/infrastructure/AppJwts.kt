// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.infrastructure

import dev.ostrom.server.integrations.github.domain.GitHubAppSource
import kotlin.time.Clock

/**
 * The JWT of the App in use, kept while the App stays the same (its key is decoded once), made anew when the App
 * changes (created by the manifest flow after the start).
 *
 * @param apps The App in use.
 * @param clock The clock.
 */
class AppJwts(private val apps: GitHubAppSource, private val clock: Clock) {
    private var held: Pair<Pair<String, String>, AppJwt>? = null

    /** @return The App's JWT, or null without an App id and key. */
    suspend fun current(): AppJwt? {
        val app = apps.current()
        val id = app.appId
        val pem = app.privateKeyPem
        if (id == null || pem == null) return null
        val kept = held?.takeIf { it.first == id to pem }?.second
        return kept ?: AppJwt(id, pem, clock).also { held = (id to pem) to it }
    }
}
