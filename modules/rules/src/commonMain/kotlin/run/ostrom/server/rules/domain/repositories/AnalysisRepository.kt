// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.domain.repositories

import run.ostrom.server.rules.domain.models.AnalysisKind
import run.ostrom.server.rules.domain.models.AnalysisRun
import kotlin.uuid.Uuid

/** The last analysis and replay of each organization. */
interface AnalysisRepository {
    /**
     * Reads the last run of a kind.
     *
     * @param orgId The organization.
     * @param kind Analysis or replay.
     * @return The run, or null when none was ever started.
     */
    suspend fun run(orgId: Uuid, kind: AnalysisKind): AnalysisRun?

    /**
     * Runs a block in one transaction of the organization.
     *
     * @param orgId The organization.
     * @param block What to do.
     * @return What the block returns.
     */
    suspend fun <T> transaction(orgId: Uuid, block: suspend AnalysisTransaction.() -> T): T
}

/** The writes of one transaction, confined to its organization. */
interface AnalysisTransaction {
    /**
     * Locks the run of a kind until the end of the transaction, even when none exists yet.
     *
     * @param kind Analysis or replay.
     * @return The run, or null when none was ever started.
     */
    suspend fun lock(kind: AnalysisKind): AnalysisRun?

    /**
     * Writes the run of its kind, replacing the last one.
     *
     * @param run The run.
     */
    suspend fun put(run: AnalysisRun)
}
