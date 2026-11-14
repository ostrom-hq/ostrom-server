// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.presentation

import run.ostrom.server.events.domain.OpenEventFeedUseCase
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator

/**
 * What the feed's route needs.
 *
 * @property orgs The member the caller acts for, resolved again while a stream is open.
 * @property credentials Whether a person's credential still works, asked again while a stream is open.
 * @property openFeed Opens the feed.
 * @property logins The members' logins, for the approval events that name an approver; null when none is bound.
 */
data class EventsRoutesDependencies(
    val orgs: PrincipalOrgResolver,
    val credentials: TokenAuthenticator,
    val openFeed: OpenEventFeedUseCase,
    val logins: MemberLogins? = null,
)
