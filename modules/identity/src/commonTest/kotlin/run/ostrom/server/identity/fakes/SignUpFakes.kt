// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.fakes

import run.ostrom.server.identity.domain.SetupLink
import run.ostrom.server.identity.domain.SetupLinksRepository
import run.ostrom.server.identity.domain.SignUpInvitations
import run.ostrom.server.identity.domain.SignUpMode
import run.ostrom.server.identity.domain.SignUpPolicyImpl
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The setup links in memory, as `identity_setup_links` keeps them.
 *
 * @param users The instance's accounts, or none.
 */
class InMemorySetupLinks(private val users: InMemoryUsersRepository? = null) : SetupLinksRepository {
    /** The stored links by token hash, with when they were used and by whom. */
    val links = mutableMapOf<String, Triple<SetupLink, Instant?, Uuid?>>()

    override suspend fun replace(link: SetupLink, tokenHash: String) {
        for ((hash, stored) in links.entries.toList()) {
            val (old, usedAt, usedBy) = stored
            val stopped = if (usedAt == null) old.copy(expiresAt = minOf(old.expiresAt, link.createdAt)) else old
            links[hash] = Triple(stopped, usedAt, usedBy)
        }
        links[tokenHash] = Triple(link, null, null)
    }

    override suspend fun consume(tokenHash: String, userId: Uuid, now: Instant): Boolean {
        if (!pending(tokenHash, now)) return false
        links[tokenHash] = Triple(links.getValue(tokenHash).first, now, userId)
        return true
    }

    override suspend fun pending(tokenHash: String, now: Instant): Boolean =
        links[tokenHash]?.let { (link, usedAt, _) -> usedAt == null && link.expiresAt > now } == true

    override suspend fun usedBy(userId: Uuid): Boolean = links.values.any { it.third == userId }

    override suspend fun anyAccount(): Boolean = users?.users?.isNotEmpty() == true

    override suspend fun owners(): Map<Uuid, Instant> =
        links.values.mapNotNull { (_, at, by) -> if (at != null && by != null) by to at else null }.toMap()
}

/** The organizations' live invitation tokens. */
class FakeSignUpInvitations(val live: MutableSet<String> = mutableSetOf()) : SignUpInvitations {
    override suspend fun isLive(token: String): Boolean = token in live
}

/** The policy of an open instance (ostrom.run), for the tests of everything but sign-up. */
fun openSignUp(clock: SettableClock = SettableClock()) =
    SignUpPolicyImpl({ SignUpMode.OPEN }, InMemorySetupLinks(), null, FakeHasher(), clock)
