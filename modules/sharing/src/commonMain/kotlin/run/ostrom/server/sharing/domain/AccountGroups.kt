// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** How `sharedWith` names a group: `group:<id>`; a login never holds a colon. */
const val GROUP_REFERENCE: String = "group:"

/**
 * The groups the shared accounts are lent to: the same rows as a group's `accounts` (`sharing_group_accounts`), read
 * and written from the account's side, every call confined to the organization it names.
 */
interface AccountGroups {
    /** The groups each of [accountIds] is lent to; an account lent to none is absent. */
    suspend fun of(orgId: Uuid, accountIds: Collection<Uuid>): Map<Uuid, Set<Uuid>>

    /** Lends [accountId] to exactly [groups]: the groups it was lent to before and not named are dropped. */
    suspend fun lend(orgId: Uuid, accountId: Uuid, groups: Set<Uuid>)

    /** Which of [groups] the organization has. */
    suspend fun existing(orgId: Uuid, groups: Set<Uuid>): Set<Uuid>

    /** The accounts lent to [memberId] through a group they are in and not paused in at [now]. */
    suspend fun lentTo(orgId: Uuid, memberId: Uuid, now: Instant): Set<Uuid>
}

/** No groups: what a use case or the pool sees when no [AccountGroups] is given (tests of other features). */
object NoAccountGroups : AccountGroups {
    override suspend fun of(orgId: Uuid, accountIds: Collection<Uuid>): Map<Uuid, Set<Uuid>> = emptyMap()

    override suspend fun lend(orgId: Uuid, accountId: Uuid, groups: Set<Uuid>) = Unit

    override suspend fun existing(orgId: Uuid, groups: Set<Uuid>): Set<Uuid> = emptySet()

    override suspend fun lentTo(orgId: Uuid, memberId: Uuid, now: Instant): Set<Uuid> = emptySet()
}

/**
 * Whom an account is lent to, as `sharedWith` names them.
 *
 * @property members The members named directly; null for the whole organization, empty when only groups are named.
 * @property groups The groups named.
 */
data class SharedWith(val members: Set<Uuid>?, val groups: Set<Uuid>)
