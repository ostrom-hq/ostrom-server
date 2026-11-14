// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.identity.domain.GitHubAppOAuthClient
import run.ostrom.server.identity.domain.GitHubAppSignIn
import run.ostrom.server.integrations.github.domain.GitHubAppSource
import run.ostrom.server.integrations.github.domain.oauthApp

/**
 * [GitHubAppSignIn] on the instance's stored App, as TeamCode signs people in through its App: its OAuth client (an id
 * and a secret that opens with the secrets key) on the GitHub the integration talks to. A stored App whose secret no
 * longer opens has no client: sign-in then reads the `github-sign-in` settings.
 *
 * @param apps The App in use.
 * @param host GitHub's addresses, the console's settings.
 */
class AppGitHubSignIn(private val apps: GitHubAppSource, private val host: GitHubHost) : GitHubAppSignIn {
    override suspend fun client(): GitHubAppOAuthClient? = apps.current().oauthApp()?.let {
        GitHubAppOAuthClient(it.clientId, it.clientSecret, host.webUrl, host.apiUrl)
    }
}
