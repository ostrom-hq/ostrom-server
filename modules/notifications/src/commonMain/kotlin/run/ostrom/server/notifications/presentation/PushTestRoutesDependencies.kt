// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.presentation

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.notifications.domain.SendTestPushUseCase

/**
 * What the test push route needs.
 *
 * @property orgs The member the caller acts for.
 * @property sendTest The test.
 */
data class PushTestRoutesDependencies(val orgs: PrincipalOrgResolver, val sendTest: SendTestPushUseCase)
