// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

/**
 * The drifts between the server's answers and the ostrom-core contract that are known and waiting for their fix: the
 * server's, then the contract's by an additive change. Each fix removes its lines; the list only gets shorter. A drift
 * absent from it fails the test that met it ([ContractObserver.finish]); a line no drift meets any more is reported by
 * `scripts/check-contract-coverage.py`. Found by the first run of the check on every served operation (#246).
 */
object KnownDrifts {
    private const val AUDIT = "The package audit's meta holds counts where the contract says strings."
    private const val RECEIPT = "The approval's receipt lacks properties the contract requires."

    /** Each drift's key ([Drift.key]) and why it waits. */
    val drifts: Map<String, String> = listOf(
        waiting(
            AUDIT,
            "listPackageAudit 200 $.items[*].meta.enforcedScripts TYPE",
            "listPackageAudit 200 $.items[*].meta.files TYPE",
            "listPackageAudit 200 $.items[*].meta.hooks TYPE",
            "listPackageAudit 200 $.items[*].meta.nodes TYPE",
            "listPackageAudit 200 $.items[*].meta.notifications TYPE",
            "listPackageAudit 200 $.items[*].meta.scripts TYPE",
            "listPackageAudit 200 $.items[*].meta.workflows TYPE",
        ),
        waiting(
            RECEIPT,
            "answerApproval 200 $.receipt.createdAt MISSING",
            "answerApproval 200 $.receipt.decisions MISSING",
            "answerApproval 200 $.receipt.initiator MISSING",
            "answerApproval 200 $.receipt.rules MISSING",
            "answerApproval 200 $.receipt.signature MISSING",
            "answerApproval 200 $.receipt.subject MISSING",
        ),
    ).flatten().toMap()

    private fun waiting(reason: String, vararg keys: String) = keys.map { it to reason }
}
