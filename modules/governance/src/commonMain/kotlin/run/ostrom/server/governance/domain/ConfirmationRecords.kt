// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.governance.domain

/**
 * Where a confirmation's answer is kept and told: the table, the audit log, and the reads waiting on it.
 *
 * @property repository The confirmations.
 * @property audit The audit log.
 * @property waits Wakes the reads waiting on a confirmation.
 */
data class ConfirmationRecords(
    val repository: ConfirmationsRepository,
    val audit: ConfirmationAudit,
    val waits: ConfirmationWaits,
)
