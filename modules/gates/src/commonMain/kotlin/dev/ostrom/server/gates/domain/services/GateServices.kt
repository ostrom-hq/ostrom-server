package dev.ostrom.server.gates.domain.services

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import kotlin.uuid.Uuid

/** Hashes the canonical preimages of [dev.ostrom.server.gates.domain.models.GateKeys]. */
interface GateDigester {
    /**
     * Hashes a text.
     *
     * @param text The canonical text.
     * @return Its SHA-256, in lower-case hex.
     */
    fun digest(text: String): String
}

/** The organization's gate settings, owned by the policies of the organization. */
interface GatePolicies {
    /**
     * Reads the self-approval setting.
     *
     * @param orgId The organization.
     * @return The setting; [SelfApprovalPolicy.SOLE_APPROVER] when the organization never chose.
     */
    suspend fun selfApproval(orgId: Uuid): SelfApprovalPolicy
}

/** Signs the receipt of a decided gate (receipts module). */
interface GateReceiptSigner {
    /**
     * Signs the receipt of a gate's decision. Idempotent: signing the same decision twice returns the same receipt.
     *
     * @param gate The decided gate.
     * @return The receipt id.
     */
    suspend fun sign(gate: Gate): String
}

/** Tells people about a gate (notifications, Slack, GitHub check, waiting hooks). Failures never undo a decision. */
interface GateNotifier {
    /**
     * A gate asks for a yes: each approver is told on their channels.
     *
     * @param gate The gate.
     * @param requests One per approver.
     */
    suspend fun requested(gate: Gate, requests: List<ApprovalRequest>)

    /**
     * A gate was answered: the other approvers' buttons are closed, the GitHub check and the waiting hook updated.
     *
     * @param gate The decided gate.
     * @param requests Its requests, as they are now.
     */
    suspend fun decided(gate: Gate, requests: List<ApprovalRequest>)
}

/** Guards writes into agent sessions (sessions module). */
interface GateSessions {
    /**
     * Refuses a gate in a closed session: its receipt is signed, nothing more is added to it; the launcher reopens a
     * segment and asks there.
     *
     * @param orgId The organization.
     * @param sessionId The session.
     * @throws dev.ostrom.server.kernel.errors.OstromException When the session is closed or unknown.
     */
    suspend fun assertOpen(orgId: Uuid, sessionId: Uuid)
}
