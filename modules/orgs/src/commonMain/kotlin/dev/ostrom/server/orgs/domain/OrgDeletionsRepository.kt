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
     * organizations.
     *
     * @param limit At most this many.
     * @return Their ids.
     */
    suspend fun deletionsToSettle(limit: Int): List<Uuid>

    /**
     * Records that every module was told of a deletion.
     *
     * @param id The organization.
     * @param at When.
     */
    suspend fun markSettled(id: Uuid, at: Instant)

    /**
     * The deleted organizations whose 30 days are over and that are not erased yet, across
     * organizations; only those every module was told of.
     *
     * @param now The time.
     * @param limit At most this many.
     * @return Their ids.
     */
    suspend fun duePurges(now: Instant, limit: Int): List<Uuid>

    /**
     * Erases what the module keeps of a deleted organization for good: its name, its GitHub link,
     * its settings. The row stays as a tombstone that keeps its short name taken, so a link never
     * leads to someone else.
     *
     * @param id The organization.
     * @param at When.
     */
    suspend fun erase(id: Uuid, at: Instant)
}
