// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.domain.DEFAULT_NOTIFICATION_PREFERENCES
import run.ostrom.server.identity.domain.NotificationPreferencesRepository
import run.ostrom.server.identity.domain.UsersRepository
import run.ostrom.server.identity.domain.WebSessionsRepository
import run.ostrom.server.notifications.domain.Approver
import run.ostrom.server.notifications.domain.Approvers
import run.ostrom.server.notifications.domain.LiveCredentials
import run.ostrom.server.notifications.domain.PersonOrganizations
import run.ostrom.server.notifications.domain.PushCredential
import run.ostrom.server.orgs.domain.MembersRepository
import run.ostrom.server.orgs.domain.OrgsRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * [Approvers] on the identity module's people and the orgs module's members: a gate names its approvers by login.
 *
 * @param users The people.
 * @param members The members of each organization.
 * @param preferences What each person wants on their phone.
 */
class MemberApprovers(
    private val users: UsersRepository,
    private val members: MembersRepository,
    private val preferences: NotificationPreferencesRepository,
) : Approvers {
    override suspend fun reach(orgId: Uuid, login: String): Approver? {
        val user = users.findByLogin(login)
        val member = user?.let { members.findByUser(orgId, it.id) }
        if (user == null || member == null) return null
        // The primary address only once it is proven: a request never goes to an address someone merely typed.
        val proven = users.listEmails(user.id).filter { it.verified }.map { it.email.lowercase() }
        val email = user.primaryEmail?.takeIf { it.lowercase() in proven }
        val pushes = (preferences.get(user.id) ?: DEFAULT_NOTIFICATION_PREFERENCES).requests
        return Approver(user.id, member.id, email, pushes)
    }
}

/**
 * [LiveCredentials] on the identity module: the phone app's token is live while neither revoked nor expired, a browser
 * session while it is among the person's live sessions.
 *
 * @param tokens The API tokens, the phone app's among them.
 * @param sessions The browser sessions.
 * @param clock What decides an expiry.
 */
class IdentityLiveCredentials(
    private val tokens: ApiTokensRepository,
    private val sessions: WebSessionsRepository,
    private val clock: Clock,
) : LiveCredentials {
    override suspend fun live(userId: Uuid, credential: PushCredential): Boolean {
        val now = clock.now()
        return if (credential.browser) {
            sessions.listLive(userId, now, LIVE_SESSIONS).any { it.id == credential.credentialId }
        } else {
            val token = tokens.get(userId, credential.credentialId)
            token != null && token.revokedAt == null && token.expiresAt.let { it == null || it > now }
        }
    }
}

/**
 * [PersonOrganizations] on the orgs module.
 *
 * @param orgs The organizations.
 */
class MemberOrganizations(private val orgs: OrgsRepository) : PersonOrganizations {
    override suspend fun of(userId: Uuid): List<Uuid> = orgs.listOfUser(userId).map { it.org.id }
}

/** The browser sessions read to find one: more than a person keeps open. */
private const val LIVE_SESSIONS = 200
