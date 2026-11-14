// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.presentation

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.notifications.domain.NotificationSettingsService

/**
 * What the notification settings routes need.
 *
 * @property orgs The member the caller acts for.
 * @property settings The member's switches.
 */
data class NotificationSettingsRoutesDependencies(
    val orgs: PrincipalOrgResolver,
    val settings: NotificationSettingsService,
)
