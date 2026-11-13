// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import run.ostrom.server.kernel.auth.MemberLogins
import kotlin.uuid.Uuid

/**
 * The people of the shared accounts, named as the API names them: by login (the contract's `owner` and `sharedWith`,
 * `/api/v1/orgs/{org}/members` serving no member id). A member id is still read where a login is expected, and still
 * written for a member with no login (one who left): what the CLI and imports sent before keeps its meaning.
 *
 * Groups are named `group:<id>` ([GROUP_REFERENCE]) in the same list: an account lent to a group is lent to its members
 * who are not paused, and the link is the group's own (`sharing_group_accounts`, through [groups]).
 *
 * @param logins The orgs module's logins, or [NoMemberLogins] when it is not loaded.
 * @param groups The groups the accounts are lent to.
 */
class MemberNames(private val logins: MemberLogins, private val groups: AccountGroups = NoAccountGroups) {
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

    /**
     * Whom [values] lend an account to in [orgId]: the members named by login or id ([ids]) and the groups named
     * `group:<id>`. Only groups: the members are an empty set, so the account is not open to the whole organization.
     *
     * @throws InvalidAccountException When a value names no member, or no group of the organization.
     */
    suspend fun sharedWith(orgId: Uuid, values: List<String>): SharedWith {
        val (references, people) = values.partition { it.startsWith(GROUP_REFERENCE) }
        val refusal = "sharedWith names groups of the organization as group:<id>."
        val named = references.map {
            Uuid.parseOrNull(it.removePrefix(GROUP_REFERENCE)) ?: throw InvalidAccountException(refusal)
        }.toSet()
        if (groups.existing(orgId, named) != named) throw InvalidAccountException(refusal)
        return SharedWith(ids(orgId, people) ?: emptySet<Uuid>().takeIf { named.isNotEmpty() }, named)
    }

    /** Lends [accountId] to exactly the groups of [sharing]. */
    suspend fun lend(orgId: Uuid, accountId: Uuid, sharing: SharedWith) = groups.lend(orgId, accountId, sharing.groups)

    /** The `group:<id>` references of the groups each of [accounts] is lent to; an account lent to none is absent. */
    suspend fun groupsOf(orgId: Uuid, accounts: List<SharedAccount>): Map<Uuid, List<String>> =
        groups.of(orgId, accounts.map { it.id }).mapValues { (_, ids) -> ids.map { "$GROUP_REFERENCE$it" } }

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

/**
 * The names of the members [account] is lent to and the references of its groups (from [MemberNames.groupsOf]), sorted;
 * empty for the whole org.
 */
internal fun Map<Uuid, String>.sharedWithOf(account: SharedAccount, groups: Map<Uuid, List<String>>): List<String> =
    (account.limits.allowedMembers.orEmpty().map(::getValue) + groups[account.id].orEmpty()).sorted()

/** No logins known: the default until the orgs module binds its [MemberLogins]; people are then named by id. */
object NoMemberLogins : MemberLogins {
    override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> = emptyMap()

    override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> = emptyMap()
}
