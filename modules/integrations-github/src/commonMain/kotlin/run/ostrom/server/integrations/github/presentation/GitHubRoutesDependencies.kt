// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.presentation

import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.integrations.github.domain.GitHubAppSource
import run.ostrom.server.integrations.github.domain.GitHubInstalls
import run.ostrom.server.integrations.github.domain.GitHubRepository
import run.ostrom.server.integrations.github.domain.HandleWebhookUseCase
import run.ostrom.server.integrations.github.domain.OrgReferences
import run.ostrom.server.integrations.github.infrastructure.GitHubHost
import run.ostrom.server.integrations.github.infrastructure.GitHubSettings
import run.ostrom.server.integrations.github.infrastructure.WebhookSignature
import run.ostrom.server.kernel.auth.PrincipalOrgResolver

/**
 * What the GitHub routes need.
 *
 * @property actors Resolves callers (the organization comes from the credential).
 * @property members The caller's role in the organization: only an owner or admin installs the App.
 * @property repository Installations.
 * @property installs The install flow.
 * @property webhooks Handles a verified webhook.
 * @property signature Verifies a webhook.
 * @property settings The console's origin.
 * @property apps The App in use: the stored one (created by the manifest flow, or imported once).
 * @property orgs Reads the organization an install start names (`?org=`).
 * @property host GitHub's web address, as the console's `github` settings set it.
 */
data class GitHubRoutesDependencies(
    val actors: GateActorResolver,
    val members: PrincipalOrgResolver,
    val repository: GitHubRepository,
    val installs: GitHubInstalls,
    val webhooks: HandleWebhookUseCase,
    val signature: WebhookSignature,
    val settings: GitHubSettings,
    val apps: GitHubAppSource,
    val orgs: OrgReferences,
    val host: GitHubHost,
)
