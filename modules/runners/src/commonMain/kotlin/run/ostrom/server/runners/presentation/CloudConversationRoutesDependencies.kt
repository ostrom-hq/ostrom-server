// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.runners.domain.CloudConversationService

/**
 * What the conversation routes need.
 *
 * @property conversation Transcript reads, inputs and the agent's options.
 * @property orgResolver The member a request acts for.
 */
data class CloudConversationRoutesDependencies(
    val conversation: CloudConversationService,
    val orgResolver: PrincipalOrgResolver,
)
