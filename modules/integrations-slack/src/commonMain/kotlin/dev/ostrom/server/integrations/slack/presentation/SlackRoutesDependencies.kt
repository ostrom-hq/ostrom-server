// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.presentation

import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.integrations.slack.domain.HandleSlackClickUseCase
import dev.ostrom.server.integrations.slack.domain.SlackLinksUseCase
import dev.ostrom.server.integrations.slack.infrastructure.SlackSignature
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver

/**
 * What the Slack routes need.
 *
 * @property actors Resolves callers (the organization comes from the credential).
 * @property members The caller's role: only an owner or an admin links members.
 * @property links The links.
 * @property clicks Answers a click.
 * @property signature Verifies an interaction.
 */
data class SlackRoutesDependencies(
    val actors: GateActorResolver,
    val members: PrincipalOrgResolver,
    val links: SlackLinksUseCase,
    val clicks: HandleSlackClickUseCase,
    val signature: SlackSignature,
)
