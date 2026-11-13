// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.events.presentation

import run.ostrom.server.events.domain.WebhooksService
import run.ostrom.server.kernel.auth.PrincipalOrgResolver

/**
 * What the webhooks' routes need.
 *
 * @property orgs The member the caller acts for.
 * @property webhooks The management of the webhooks.
 */
data class WebhooksRoutesDependencies(val orgs: PrincipalOrgResolver, val webhooks: WebhooksService)
