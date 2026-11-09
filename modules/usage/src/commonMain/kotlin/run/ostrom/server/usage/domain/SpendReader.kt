// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What has been spent, read from the ledger. Bound in Koin by the usage module; the budgets and sharing read it. */
interface SpendReader {
    /** USD millionths spent by the org (memberId null) or one member since [since]. */
    suspend fun spentMicros(orgId: Uuid, memberId: Uuid?, since: Instant): Long

    /** Same, for one pooled account (per-account limits of personal account sharing). */
    suspend fun accountSpentMicros(orgId: Uuid, accountId: Uuid, since: Instant): Long

    /** Same, for the model calls of the launches that play one workflow (its budgets). */
    suspend fun workflowSpentMicros(orgId: Uuid, workflowId: String, since: Instant): Long
}

/**
 * [SpendReader] on the ledger.
 *
 * @param repository The ledger.
 */
class SpendReaderImpl(private val repository: UsageRepository) : SpendReader {
    override suspend fun spentMicros(orgId: Uuid, memberId: Uuid?, since: Instant): Long =
        repository.spentMicros(orgId, memberId, since)

    override suspend fun accountSpentMicros(orgId: Uuid, accountId: Uuid, since: Instant): Long =
        repository.accountSpentMicros(orgId, accountId, since)

    override suspend fun workflowSpentMicros(orgId: Uuid, workflowId: String, since: Instant): Long =
        repository.workflowSpentMicros(orgId, workflowId, since)
}
