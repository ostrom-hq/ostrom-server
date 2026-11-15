// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.presentation

import run.ostrom.server.integrations.slack.domain.GetSlackLinkUseCase
import run.ostrom.server.integrations.slack.domain.LinkSlackUseCase
import run.ostrom.server.integrations.slack.domain.ReceiveSlackInteractionUseCase
import run.ostrom.server.integrations.slack.domain.UnlinkSlackUseCase
import run.ostrom.server.kernel.auth.PrincipalOrgResolver

/**
 * What the Slack routes need.
 *
 * @property orgs Resolves the caller's membership.
 * @property get Reads the caller's link.
 * @property link Links the caller.
 * @property unlink Removes the caller's link.
 * @property interactions Receives Slack's interactions.
 */
data class SlackRoutesDependencies(
    val orgs: PrincipalOrgResolver,
    val get: GetSlackLinkUseCase,
    val link: LinkSlackUseCase,
    val unlink: UnlinkSlackUseCase,
    val interactions: ReceiveSlackInteractionUseCase,
)
