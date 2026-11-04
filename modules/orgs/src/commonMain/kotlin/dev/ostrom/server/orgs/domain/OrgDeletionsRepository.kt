// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The end of the organizations' deletions, for [SettleOrgDeletionsUseCase]: finding the deleted
 * ones crosses organizations, changing one stays inside it.
 */
interface OrgDeletionsRepository {
    /**
     * The deleted organizations the modules were not told of yet, oldest deletion first, across
     * organizations; not those postponed past [now].
     *
     * @param now The time.
     * @param limit At most this many.
     * @return Their ids.
     */
    suspend fun deletionsToSettle(now: Instant, limit: Int): List<Uuid>

    /**
     * Records that every module was told of a deletion.
     *
     * @param id The organization.
     * @param at When.
     */
    suspend fun markSettled(id: Uuid, at: Instant)

    /**
     * The deleted organizations whose 30 days are over and that are not erased yet, across
     * organizations; only those every module was told of, and not those postponed past [now].
     *
     * @param now The time.
     * @param limit At most this many.
     * @return Their ids.
     */
    suspend fun duePurges(now: Instant, limit: Int): List<Uuid>

    /**
     * Erases what the module keeps of a deleted organization for good: its name, its GitHub link,
     * its settings, and its short name, taken until now so that it could be restored, and free from
     * now on (as on GitHub, short names are one space for everybody). The row stays as a tombstone
     * under a name nobody can choose.
     *
     * @param id The organization.
     * @param at When.
     */
    suspend fun erase(id: Uuid, at: Instant)

    /**
     * Puts off the next step of a deletion a module failed on, so that it does not hold the
     * others back.
     *
     * @param id The organization.
     * @param until When to try again.
     */
    suspend fun postpone(id: Uuid, until: Instant)
}
