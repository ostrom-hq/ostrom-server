// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.domain

import dev.ostrom.server.kernel.module.OrgDeletionListener
import dev.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** How many organizations one pass settles, then purges, at most. */
const val DELETION_BATCH = 50

/**
 * Finishes the deletions, across organizations: tells every module of each new deletion
 * ([OrgDeletionListener.deleted]: billing ends the subscription), then, once [DELETION_GRACE] is
 * over, has every module erase its data ([OrgDeletionListener.purge]) and erases the organization.
 * Every replica runs it: each step is idempotent.
 */
interface SettleOrgDeletionsUseCase {
    /**
     * Runs one pass.
     *
     * @return What it did.
     */
    suspend operator fun invoke(): DeletionPass
}

/**
 * [SettleOrgDeletionsUseCase]. An organization a module fails on is left as it is, logged with its
 * id and the error's class, and postponed by [DELETION_RETRY]: the others go first, and a step that
 * always fails never fills a batch.
 *
 * @param deletions The deleted organizations.
 * @param listeners The modules told of a deletion.
 * @param clock The clock.
 * @param logger Where a failed step is reported (never the error's message).
 * @param batch How many organizations of each kind one pass takes.
 */
class SettleOrgDeletionsUseCaseImpl(
    private val deletions: OrgDeletionsRepository,
    private val listeners: List<OrgDeletionListener>,
    private val clock: Clock,
    private val logger: Logger,
    private val batch: Int = DELETION_BATCH,
) : SettleOrgDeletionsUseCase {
    override suspend fun invoke(): DeletionPass {
        val toSettle = deletions.deletionsToSettle(clock.nowMillis(), batch)
        val settled = toSettle.count { id ->
            succeeds(id, "settle") {
                listeners.forEach { it.deleted(id) }
                deletions.markSettled(id, clock.nowMillis())
            }
        }
        val toPurge = deletions.duePurges(clock.nowMillis(), batch)
        val purged = toPurge.count { id ->
            succeeds(id, "purge") {
                listeners.forEach { it.purge(id) }
                deletions.erase(id, clock.nowMillis())
            }
        }
        return DeletionPass(settled, purged, toSettle.size - settled + toPurge.size - purged)
    }

    // A module's failure on one organization never stops the others; a cancellation stops the pass.
    private suspend fun succeeds(id: Uuid, step: String, block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val fields = mapOf("org_id" to id.toString(), "step" to step, "error" to error::class.simpleName)
            logger.warn("organization deletion step failed", fields)
            postpone(id)
            false
        }

    private suspend fun postpone(id: Uuid) {
        try {
            deletions.postpone(id, clock.nowMillis() + DELETION_RETRY)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The step is tried again at the next pass instead.
            logger.warn("organization deletion not postponed", mapOf("error" to error::class.simpleName))
        }
    }
}
