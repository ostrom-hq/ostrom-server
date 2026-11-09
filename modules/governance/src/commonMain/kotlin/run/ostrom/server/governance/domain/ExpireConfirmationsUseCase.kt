// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.governance.domain

import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Writes the expiry of the confirmations left unanswered past their 15 minutes, each with its audit event. */
interface ExpireConfirmationsUseCase {
    /**
     * Expires those of one organization: every use case does it first, so a confirmation is never read as pending
     * after its expiry.
     *
     * @param orgId The organization.
     */
    suspend fun inOrganization(orgId: Uuid)

    /**
     * Expires those of every organization (the module's sweep, once a minute), so each expiry has its event even
     * when nobody reads the confirmation again.
     *
     * @return How many organizations it looked at.
     */
    suspend fun everywhere(): Int

    /** The bound of one sweep. */
    companion object {
        /** The organizations one sweep looks at; the next sweep takes the rest. */
        const val ORGANIZATIONS_PER_SWEEP: Int = 100
    }
}

/**
 * [ExpireConfirmationsUseCase] on the repository and the audit log.
 *
 * @param repository The confirmations.
 * @param audit The audit log.
 * @param waits Wakes the reads waiting on an expired confirmation.
 * @param clock The time.
 */
class ExpireConfirmationsUseCaseImpl(
    private val repository: ConfirmationsRepository,
    private val audit: ConfirmationAudit,
    private val waits: ConfirmationWaits,
    private val clock: Clock,
) : ExpireConfirmationsUseCase {
    override suspend fun inOrganization(orgId: Uuid) {
        var failure: Exception? = null
        for (expired in repository.expire(orgId, clock.now())) {
            waits.changed(expired.id)
            // One failed event does not cost the others theirs: every expiry is tried, the first failure rethrown.
            try {
                audit.record(expired, ConfirmationStep.EXPIRED, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = failure ?: error
            }
        }
        failure?.let { throw it }
    }

    override suspend fun everywhere(): Int {
        val organizations =
            repository.organizationsWithExpired(clock.now(), ExpireConfirmationsUseCase.ORGANIZATIONS_PER_SWEEP)
        organizations.forEach { inOrganization(it) }
        return organizations.size
    }
}
