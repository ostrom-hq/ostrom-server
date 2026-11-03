// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.domain

import dev.ostrom.server.kernel.auth.MemberLogins
import kotlin.uuid.Uuid

/**
 * The people of the shared accounts, named as the API names them: by login (the contract's `owner` and `sharedWith`,
 * `/api/v1/orgs/{org}/members` serving no member id). A member id is still read where a login is expected, and still
 * written for a member with no login (one who left): what the CLI and imports sent before keeps its meaning.
 *
 * @param logins The orgs module's logins, or [NoMemberLogins] when it is not loaded.
 */
class MemberNames(private val logins: MemberLogins) {
    /**
     * The members [values] name in [orgId]: a login of one of its members, or a member id.
     *
     * @return Their ids, or null for an empty list (the whole org).
     *
     * @throws InvalidAccountException When a value names no member, or there are too many.
     */
    suspend fun ids(orgId: Uuid, values: List<String>): Set<Uuid>? {
        if (values.isEmpty()) return null
        if (values.size > SharingRules.MAX_ALLOWED_MEMBERS) {
            throw InvalidAccountException("At most ${SharingRules.MAX_ALLOWED_MEMBERS} allowed members.")
        }
        val byLogin = logins.idsOf(orgId, values)
        return values.map {
            byLogin[it] ?: Uuid.parseOrNull(it)
                ?: throw InvalidAccountException("sharedWith names members of the organization by login.")
        }.toSet()
    }

    /** The name of every member [accounts] name (owners, allowed members): its login, else its id. */
    suspend fun of(orgId: Uuid, accounts: List<SharedAccount>): Map<Uuid, String> {
        val ids = accounts.flatMap { listOfNotNull(it.ownerMemberId) + it.limits.allowedMembers.orEmpty() }.toSet()
        val known = logins.loginsOf(orgId, ids)
        return ids.associateWith { known[it] ?: it.toString() }
    }
}

/** The name of [account]'s owner in these names (from [MemberNames.of]); empty when an admin added it. */
internal fun Map<Uuid, String>.ownerOf(account: SharedAccount): String =
    account.ownerMemberId?.let(::getValue).orEmpty()

/** The names of the members [account] is lent to, sorted; empty for the whole org. */
internal fun Map<Uuid, String>.sharedWithOf(account: SharedAccount): List<String> =
    account.limits.allowedMembers.orEmpty().map(::getValue).sorted()

/** No logins known: the default until the orgs module binds its [MemberLogins]; people are then named by id. */
object NoMemberLogins : MemberLogins {
    override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> = emptyMap()

    override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> = emptyMap()
}
