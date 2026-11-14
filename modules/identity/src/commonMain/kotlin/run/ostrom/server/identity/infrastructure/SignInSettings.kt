// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.GitHubAppSignIn
import run.ostrom.server.identity.domain.SignUpMode
import run.ostrom.server.identity.domain.trimmedOrNull
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.kernel.settings.SettingsValues

/**
 * Sign-in with Google, set by an administrator of the instance in the console: the OAuth client, and Google's
 * endpoints replaced one by one (a fake Google in end-to-end tests). Offered once the client id and secret are set.
 */
val GOOGLE_SIGN_IN_SETTINGS: SettingsSection = SettingsSection(
    "google",
    listOf(
        SettingsField("clientId", required = true, legacyVariable = "OSTROM_GOOGLE_CLIENT_ID"),
        SettingsField("clientSecret", secret = true, required = true, legacyVariable = "OSTROM_GOOGLE_CLIENT_SECRET"),
        SettingsField("issuer", legacyVariable = "OSTROM_GOOGLE_ISSUER", check = ::webUrlProblem),
        SettingsField("authorizationUrl", legacyVariable = "OSTROM_GOOGLE_AUTHORIZATION_URL", check = ::webUrlProblem),
        SettingsField("tokenUrl", legacyVariable = "OSTROM_GOOGLE_TOKEN_URL", check = ::webUrlProblem),
    ),
)

/**
 * Sign-in with GitHub, set by an administrator of the instance in the console: the OAuth client people sign in with,
 * and GitHub's web and API URLs (GitHub Enterprise Server, or a fake GitHub in end-to-end tests). Used only while the
 * instance's GitHub App has no usable OAuth client ([GitHubAppSignIn]): the App's is used first, as TeamCode signs in
 * through its App. Offered once the client id and secret are set; `ostrom login` with a GitHub token checks it against
 * the same client.
 */
val GITHUB_SIGN_IN_SETTINGS: SettingsSection = SettingsSection(
    "github-sign-in",
    listOf(
        SettingsField("clientId", required = true, legacyVariable = "OSTROM_GITHUB_CLIENT_ID"),
        SettingsField("clientSecret", secret = true, required = true, legacyVariable = "OSTROM_GITHUB_CLIENT_SECRET"),
        SettingsField("webUrl", legacyVariable = "OSTROM_GITHUB_WEB_URL", check = ::webUrlProblem),
        SettingsField("apiUrl", legacyVariable = "OSTROM_GITHUB_API_URL", check = ::webUrlProblem),
    ),
)

/** Who may get a new account on the instance (`invite`, the default, or `open`), set in the console. */
val SIGN_UP_SETTINGS: SettingsSection = SettingsSection(
    "sign-up",
    listOf(
        SettingsField("mode", legacyVariable = "OSTROM_SIGNUP") {
            if (SignUpMode.of(it) == null) "invite or open." else null
        },
    ),
)

/**
 * GitHub's sign-in as set now.
 *
 * @property client The OAuth client.
 * @property webUrl GitHub's web URL, without a trailing slash.
 * @property apiUrl GitHub's API URL, without a trailing slash.
 */
data class GitHubSignIn(val client: OAuthClient, val webUrl: String, val apiUrl: String)

/**
 * Google's sign-in as set now.
 *
 * @property client The OAuth client.
 * @property endpoints Google's endpoints, or those that replace them.
 */
data class GoogleSignIn(val client: OAuthClient, val endpoints: OidcEndpoints)

/**
 * Reads the sign-in settings an administrator set in the console, at each use (the instance's settings are kept 30
 * seconds per replica): a provider is offered once its client id and secret are set, without a restart.
 *
 * @param settings The instance's settings.
 * @param app The instance's GitHub App, whose OAuth client GitHub's sign-in uses first.
 */
class SignInSettings(private val settings: InstanceSettings, private val app: GitHubAppSignIn) {
    /**
     * GitHub's sign-in: through the instance's GitHub App when its OAuth client is known (its id and a secret that
     * opens), as TeamCode does; otherwise the `github-sign-in` settings. Closed when neither is usable.
     *
     * @return It, or null while neither the App's client nor the settings' is set.
     */
    suspend fun github(): GitHubSignIn? = app.client()?.let {
        GitHubSignIn(OAuthClient(it.clientId, it.clientSecret), it.webUrl.trimEnd('/'), it.apiUrl.trimEnd('/'))
    } ?: gitHubSettings()

    private suspend fun gitHubSettings(): GitHubSignIn? {
        val values = settings.read(GITHUB_SIGN_IN_SETTINGS.name)
        val client = values.client() ?: return null
        val webUrl = values.value("webUrl") ?: "https://github.com"
        val apiUrl = values.value("apiUrl") ?: "https://api.github.com"
        return GitHubSignIn(client, webUrl.trimEnd('/'), apiUrl.trimEnd('/'))
    }

    /**
     * Google's sign-in.
     *
     * @return It, or null while its client id or secret is unset.
     */
    suspend fun google(): GoogleSignIn? {
        val values = settings.read(GOOGLE_SIGN_IN_SETTINGS.name)
        val client = values.client() ?: return null
        val issuer = values.value("issuer")
        val google = OidcEndpoints.GOOGLE
        val endpoints = google.copy(
            issuers = if (issuer == null) google.issuers else setOf(issuer),
            authorizationEndpoint = values.value("authorizationUrl") ?: google.authorizationEndpoint,
            tokenEndpoint = values.value("tokenUrl") ?: google.tokenEndpoint,
        )
        return GoogleSignIn(client, endpoints)
    }

    /**
     * Who may get a new account.
     *
     * @return The mode: [SignUpMode.INVITE] while unset (or set to what the console would refuse).
     */
    suspend fun signUp(): SignUpMode =
        SignUpMode.of(settings.read(SIGN_UP_SETTINGS.name).value("mode")) ?: SignUpMode.INVITE

    private fun SettingsValues.client(): OAuthClient? {
        val id = value("clientId")
        val secret = value("clientSecret")
        return if (id == null || secret == null) null else OAuthClient(id, secret)
    }

    private fun SettingsValues.value(field: String): String? = trimmedOrNull(this[field], Int.MAX_VALUE)
}

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
