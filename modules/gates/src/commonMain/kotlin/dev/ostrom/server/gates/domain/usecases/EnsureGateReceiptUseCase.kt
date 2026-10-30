// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.usecases

import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException

/**
 * Makes sure a decided gate has its signed receipt. The decision is committed before the receipt is signed (signing
 * never holds the gate's lock); if signing failed, the next read signs it.
 */
interface EnsureGateReceiptUseCase {
    /**
     * Signs the receipt of a decided gate that has none yet.
     *
     * @param gate The gate.
     * @return The gate with its receipt id, or unchanged when it is pending, already signed, or signing failed.
     */
    suspend fun ensureSigned(gate: Gate): Gate
}

/**
 * [EnsureGateReceiptUseCase] with the receipts module's signer.
 *
 * @param repository The gates.
 * @param signer Signs (idempotent for one decision).
 * @param logger Logs signing failures.
 */
class EnsureGateReceiptUseCaseImpl(
    private val repository: GatesRepository,
    private val signer: GateReceiptSigner,
    private val logger: Logger,
) : EnsureGateReceiptUseCase {
    override suspend fun ensureSigned(gate: Gate): Gate {
        if (gate.receiptId != null || gate.decidedAt == null || gate.status == GateStatus.PENDING) return gate
        return try {
            val receiptId = signer.sign(gate)
            repository.transaction {
                // Re-read under lock: a session may have reserved the yes while the receipt was being signed.
                val current = lockGate(gate.orgId, gate.id)
                if (current == null) {
                    gate.copy(receiptId = receiptId)
                } else {
                    current.copy(receiptId = receiptId).also { updateGate(it) }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error("gate receipt signing failed", mapOf("gate_id" to gate.id), error)
            gate
        }
    }
}
