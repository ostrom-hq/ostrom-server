// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.presentation

import run.ostrom.server.events.domain.EventAudience
import run.ostrom.server.events.domain.EventRules
import run.ostrom.server.events.domain.EventsScopeException
import run.ostrom.server.events.domain.InvalidCursorException
import run.ostrom.server.events.domain.InvalidEventTypeException
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.ServiceKeyScopes
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.auth.outsideOrganization
import run.ostrom.server.kernel.events.OrgEventType
import kotlin.uuid.Uuid

/** The scope that reads the feed, as it reads the cloud sessions (TeamCode: `cloud:read`). */
private const val CLOUD_READ = "cloud:read"

/** A token that writes the cloud sessions reads them too. */
private const val CLOUD_WRITE = "cloud:write"

/** The roles that read the whole organization's events (those of no member; another member's never, TeamCode #238). */
private val ADMIN_ROLES = setOf(OrgRole.OWNER, OrgRole.ADMIN)

/**
 * Whether a credential may read the feed: a person's browser session, or a token holding `cloud:read`, `cloud:write`
 * or, for a service key, `cloud:admin`. Never an agent's token, which holds none of them.
 */
internal fun AuthenticatedPrincipal.readsEvents(): Boolean =
    allows(CLOUD_READ) || CLOUD_WRITE in scopes ||
        (kind == PrincipalKind.SERVICE && ServiceKeyScopes.CLOUD_ADMIN in scopes)

/**
 * Whether a reader sees the whole organization's events: an owner or an admin; a service key only with `cloud:admin` or
 * `team:read` (the resolver has checked its creator still is one), a member's key never.
 */
internal fun AuthenticatedPrincipal.readsWholeOrg(member: OrgPrincipal): Boolean = if (kind == PrincipalKind.SERVICE) {
    ServiceKeyScopes.CLOUD_ADMIN in scopes || ServiceKeyScopes.TEAM_READ in scopes
} else {
    member.role in ADMIN_ROLES
}

/**
 * The member a feed's reader acts for and what they see. The scope first: a credential that may not read learns
 * nothing about its organization.
 *
 * @param principal The caller.
 *
 * @return The member and their audience.
 *
 * @throws EventsScopeException When the credential may not read the feed.
 */
internal suspend fun PrincipalOrgResolver.readerOf(
    principal: AuthenticatedPrincipal,
): Pair<OrgPrincipal, EventAudience> {
    if (!principal.readsEvents()) throw EventsScopeException()
    val member = resolve(principal, null) ?: throw principal.outsideOrganization()
    return member to EventAudience(member.memberId, principal.readsWholeOrg(member))
}

/**
 * The audience with the reader's login, so that an approver reads the approval events that name them. Asked when the
 * feed opens, with no gate's lock held.
 *
 * @param orgId The organization.
 * @param logins The members' logins, or null when none is bound (the approver then reads none).
 *
 * @return The audience, with the login when the reader is a member of [orgId].
 */
internal suspend fun EventAudience.withLogin(orgId: Uuid, logins: MemberLogins?): EventAudience =
    copy(login = logins?.loginsOf(orgId, listOf(memberId))?.get(memberId))

/**
 * Whether an open stream's reader still reads what it was opened with: a person's credential still works, and the
 * credential still resolves to the same member with no less than the same audience.
 *
 * @param credentials Asked whether a person's credential still works.
 * @param principal The caller, as authenticated when the stream opened.
 * @param member The member it acted for.
 * @param audience What it saw.
 *
 * @return True while the stream may go on.
 */
internal suspend fun PrincipalOrgResolver.stillReads(
    credentials: TokenAuthenticator,
    principal: AuthenticatedPrincipal,
    member: OrgPrincipal,
    audience: EventAudience,
): Boolean {
    val credential = principal.credentialId
    val live = principal.kind != PrincipalKind.USER || credential == null ||
        credentials.isLive(principal.subjectId, credential)
    val now = if (live) resolve(principal, member.orgId) else null
    return now != null && now.memberId == member.memberId && (!audience.wholeOrg || principal.readsWholeOrg(now))
}

/**
 * The cursor a client resumes from: `Last-Event-ID` wins over `?after=`.
 *
 * @param header The `Last-Event-ID` header.
 * @param query The `after` query parameter.
 *
 * @return The sequence, or null to start at the newest event.
 *
 * @throws InvalidCursorException When the one given is not a non-negative number.
 */
internal fun cursorOf(header: String?, query: String?): Long? {
    val text = header ?: query ?: return null
    return text.trim().toLongOrNull()?.takeIf { it >= 0 } ?: throw InvalidCursorException()
}

/**
 * The types a stream keeps: comma-separated wire names this server knows, at most [EventRules.MAX_TYPES].
 *
 * @param query The `types` query parameter.
 *
 * @return The wire names, or null for every type.
 *
 * @throws InvalidEventTypeException When a name is unknown or there are too many.
 */
internal fun typesOf(query: String?): Set<String>? {
    val names = query.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    if (names.isEmpty()) return null
    if (names.size > EventRules.MAX_TYPES || names.any { OrgEventType.parse(it) == null }) {
        throw InvalidEventTypeException()
    }
    return names
}
