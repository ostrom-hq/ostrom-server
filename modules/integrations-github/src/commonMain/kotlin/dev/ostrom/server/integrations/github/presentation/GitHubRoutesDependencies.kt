// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.presentation

import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.integrations.github.domain.GitHubInstalls
import dev.ostrom.server.integrations.github.domain.GitHubRepository
import dev.ostrom.server.integrations.github.domain.HandleWebhookUseCase
import dev.ostrom.server.integrations.github.infrastructure.GitHubSettings
import dev.ostrom.server.integrations.github.infrastructure.WebhookSignature
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver

/**
 * What the GitHub routes need.
 *
 * @property actors Resolves callers (the organization comes from the credential).
 * @property members The caller's role in the organization: only an owner or admin installs the App.
 * @property repository Installations.
 * @property installs The install flow.
 * @property webhooks Handles a verified webhook.
 * @property signature Verifies a webhook.
 * @property settings The App.
 */
data class GitHubRoutesDependencies(
    val actors: GateActorResolver,
    val members: PrincipalOrgResolver,
    val repository: GitHubRepository,
    val installs: GitHubInstalls,
    val webhooks: HandleWebhookUseCase,
    val signature: WebhookSignature,
    val settings: GitHubSettings,
)
