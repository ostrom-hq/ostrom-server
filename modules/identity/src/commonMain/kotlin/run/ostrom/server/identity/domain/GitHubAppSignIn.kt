// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.domain

/**
 * The OAuth client of the instance's GitHub App, and the GitHub it lives on, for signing in with GitHub through the
 * App as TeamCode does (its web flow authorizes with the App's client id and exchanges the code with its secret).
 *
 * @property clientId The App's OAuth client id.
 * @property clientSecret Its secret: never in a log nor a dump.
 * @property webUrl GitHub's web address, without a trailing slash.
 * @property apiUrl GitHub's API, without a trailing slash.
 */
class GitHubAppOAuthClient(val clientId: String, val clientSecret: String, val webUrl: String, val apiUrl: String) {
    override fun toString(): String = "GitHubAppOAuthClient(clientId=$clientId, webUrl=$webUrl)"
}

/**
 * The instance's GitHub App as sign-in reads it: a port the integrations-github module implements (identity depends on
 * no integration), bound in Koin when that module is in the server.
 */
fun interface GitHubAppSignIn {
    /** @return The App's OAuth client, or null while no App is stored or its client or secret is unknown. */
    suspend fun client(): GitHubAppOAuthClient?
}

/** Without the integrations-github module there is no App: sign-in with GitHub reads its own settings only. */
object NoGitHubAppSignIn : GitHubAppSignIn {
    override suspend fun client(): GitHubAppOAuthClient? = null
}
