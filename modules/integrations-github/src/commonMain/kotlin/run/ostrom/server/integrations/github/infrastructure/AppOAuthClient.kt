// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.GitHubAppCredentials

/**
 * The GitHub App's OAuth client, which exchanges the code of the install's return for the person's token.
 *
 * @property id The client id.
 * @property secret The client secret: never in a log nor a dump.
 */
class AppOAuthClient(val id: String, val secret: String) {
    override fun toString(): String = "AppOAuthClient(id=$id)"
}

/** @return The App's OAuth client, when both its id and its secret are known. */
fun GitHubAppCredentials.oauthClient(): AppOAuthClient? {
    val id = clientId
    val secret = clientSecret
    return if (id == null || secret == null) null else AppOAuthClient(id, secret)
}
