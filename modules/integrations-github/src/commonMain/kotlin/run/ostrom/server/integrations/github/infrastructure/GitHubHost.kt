// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection
import kotlin.concurrent.Volatile

/**
 * Where the instance's GitHub is and what the App the console creates is called, set by an administrator of the
 * instance in the console: GitHub's API and web URLs (GitHub Enterprise Server, or a fake GitHub in end-to-end tests),
 * and the name of the App the manifest flow creates (unique on GitHub).
 */
val GITHUB_SETTINGS: SettingsSection = SettingsSection(
    "github",
    listOf(
        SettingsField("apiUrl", legacyVariable = "OSTROM_GITHUB_API", check = ::webUrlProblem),
        SettingsField("webUrl", legacyVariable = "OSTROM_GITHUB_WEB", check = ::webUrlProblem),
        SettingsField("appName", legacyVariable = "OSTROM_GITHUB_APP_NAME") {
            if (it.trim().length in 1..MAX_APP_NAME) null else "1 to $MAX_APP_NAME characters."
        },
    ),
)

/**
 * The [GITHUB_SETTINGS] as the integration reads them on every call, without waiting: what [refresh] read last
 * (at start, then every 30 seconds while the server runs), GitHub.com and `Ostrom` while nothing is set.
 *
 * @param settings The instance's settings.
 */
class GitHubHost(private val settings: InstanceSettings) {
    // Replaced whole: a reader sees the old URLs or the new ones, never one of each.
    @Volatile
    private var held: Triple<String, String, String> = Triple(DEFAULT_API, DEFAULT_WEB, DEFAULT_NAME)

    /** GitHub's API, without a trailing slash. */
    val apiUrl: String get() = held.first

    /** GitHub's web address, without a trailing slash. */
    val webUrl: String get() = held.second

    /** The name of the App the manifest flow creates. */
    val appName: String get() = held.third

    /** Reads the settings again. */
    suspend fun refresh() {
        val values = settings.read(GITHUB_SETTINGS.name)
        fun value(field: String) = values[field]?.let { it.trim().ifEmpty { null } }
        held = Triple(
            (value("apiUrl") ?: DEFAULT_API).trimEnd('/'),
            (value("webUrl") ?: DEFAULT_WEB).trimEnd('/'),
            value("appName") ?: DEFAULT_NAME,
        )
    }

    private companion object {
        const val DEFAULT_API = "https://api.github.com"
        const val DEFAULT_WEB = "https://github.com"
        const val DEFAULT_NAME = "Ostrom"
    }
}

/** The longest name GitHub gives an App. */
private const val MAX_APP_NAME = 34

/**
 * Why a URL setting is refused.
 *
 * @param value The value.
 *
 * @return Why, or null for an `http://` or `https://` URL.
 */
internal fun webUrlProblem(value: String): String? {
    val url = value.trim()
    return if (url.startsWith("https://") || url.startsWith("http://")) null else "an http:// or https:// URL."
}
