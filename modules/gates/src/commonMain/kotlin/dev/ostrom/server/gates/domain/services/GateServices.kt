// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.services

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GatePullRequest
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
     * @param sessionId The session segment.
     * @param launchId The launch of the agent token that asks, or null for a person's device: the session must be one
     * of that launch's (audit A12).
     * @throws dev.ostrom.server.kernel.errors.OstromException When the session is closed, unknown, or of another
     *   launch.

     */
    suspend fun assertOpen(orgId: Uuid, sessionId: String, launchId: String?)
}

/** Finds the gate node that covers an action (the compiled workflows of the organization). */
interface GateNodeResolver {
    /**
     * Finds the gate of an action. A workflow without a repository applies to every repository of the organization.
     *
     * @param orgId The organization.
     * @param repo The repository, or null outside one.
     * @param actionClass The action class.
     * @return The gate node, or null when no workflow gates the action.
     */
    suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String): GateNode?
}

/** Reads pull requests from GitHub (integrations-github), never from a ref the agent can move. */
interface PullRequestHeads {
    /**
     * Reads a pull request's head commit and base branch.
     *
     * @param orgId The organization (its GitHub installation).
     * @param repo The repository (`owner/name`).
     * @param number The pull request number.
     * @return The pull request, or null when GitHub does not answer or does not know it.
     */
    suspend fun head(orgId: Uuid, repo: String, number: Int): GatePullRequest?
}
