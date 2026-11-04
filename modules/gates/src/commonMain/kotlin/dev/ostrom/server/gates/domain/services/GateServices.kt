// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.services

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.CiCheck
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import kotlinx.serialization.json.JsonObject
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
     * @return The setting; [SelfApprovalPolicy.DEFAULT] when the organization never chose (S2-15).
     */
    suspend fun selfApproval(orgId: Uuid): SelfApprovalPolicy
}

/** The GitHub account each person linked, owned by integrations-github (Q-GATES-G1). */
fun interface GateGitHubAccounts {
    /**
     * Reads the GitHub accounts a person linked.
     *
     * @param login The person's Ostrom login.
     * @return The GitHub account ids; empty when they linked none.
     */
    suspend fun gitHubIdsOf(login: String): List<Long>
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

    /**
     * Reads a signed receipt as the receipts module stores it, signature included: the answer to a yes or a no carries
     * it (contract `answerApproval`).
     *
     * @param orgId The organization.
     * @param receiptId The receipt, or null when the decision is not signed yet.
     * @return The receipt, or null when there is none to read.
     */
    suspend fun read(orgId: Uuid, receiptId: String?): JsonObject? = null
}

/**
 * One way to tell about a gate: the GitHub check, Slack, e-mail, push. Each integration binds its own (Koin
 * `bind GateChannel::class`); the default [GateNotifier] calls them all, and one failing never stops the others.
 */
interface GateChannel {
    /** The channel's name, in logs. */
    val name: String

    /**
     * A gate asks for a yes.
     *
     * @param gate The gate.
     * @param requests One per approver.
     */
    suspend fun requested(gate: Gate, requests: List<ApprovalRequest>)

    /**
     * A gate was answered.
     *
     * @param gate The decided gate.
     * @param requests Its requests, as they are now.
     */
    suspend fun decided(gate: Gate, requests: List<ApprovalRequest>)
}

/**
 * Tells people about a gate (notifications, Slack, GitHub check, waiting hooks). It never throws: a failure is logged
 * where it happens and never undoes a request or a decision.
 */
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

/**
 * The segment of an agent session open now, as [GateSessions.openSegment] finds it.
 *
 * @property id The open segment.
 * @property launchSegments The segments of the agent session the open segment's launch opened, the open one included,
 *   oldest first: the launcher reopens one in the same launch after the machine slept, another launch (a resumed
 *   session) starts its own.
 */
data class OpenSegment(val id: String, val launchSegments: List<String>)

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

    /**
     * The segment open now of the agent session [sessionId] names: its first id, or a segment the launcher has since
     * replaced (it reopens one after the machine slept, without telling the relay), so that a caller does not have to
     * know which one is open. The refusals are [assertOpen]'s, judged on that segment.
     *
     * @param orgId The organization.
     * @param sessionId Any segment of the agent session.
     * @param launchId The launch of the agent token that asks, or null for a person's device: the open segment must be
     *   one of that launch's (audit A12).
     * @return The open segment, and the segments of the session its launch opened.
     * @throws dev.ostrom.server.kernel.errors.OstromException When no segment is open, the session is unknown, or the
     *   open segment is another launch's.
     */
    suspend fun openSegment(orgId: Uuid, sessionId: String, launchId: String?): OpenSegment {
        // A port that keeps no segments knows only the one it is named.
        assertOpen(orgId, sessionId, launchId)
        return OpenSegment(sessionId, listOf(sessionId))
    }

    /**
     * Names the coding agent of a session, for the receipt of a gate it asked.
     *
     * @param orgId The organization.
     * @param sessionId The session segment.
     * @return The agent (`claude-code`, `codex`…), or null when the session is unknown (the default: a port that
     *   does not keep sessions knows no agent).
     */
    suspend fun agentOf(orgId: Uuid, sessionId: String): String? = null
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

    /**
     * Finds the gates of several action classes of one ask on one read: a source that compiles the organization's
     * workflows and rules for each read compiles them once. By default, [resolve] for each class.
     *
     * @param orgId The organization.
     * @param repo The repository, or null outside one.
     * @return The gate node of an action class, or null when no workflow gates it.
     */
    suspend fun resolver(orgId: Uuid, repo: String?): suspend (String) -> GateNode? = { resolve(orgId, repo, it) }
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

/**
 * Reads what a CI ran on a commit from the forge (integrations-github, with the organization's installation), never
 * from the workstation: the `ci` attestation of a receipt's check node (decision of 30/10 15:14).
 */
interface CiChecks {
    /**
     * The latest completed run of a check on a commit, by another App than Ostrom's.
     *
     * @param orgId The organization (its GitHub installation).
     * @param repo The repository (`owner/name`).
     * @param sha The commit.
     * @param name The check's name.
     * @return The run, or null when the forge has none, does not answer, or the organization has no installation.
     */
    suspend fun latest(orgId: Uuid, repo: String, sha: String, name: String): CiCheck?
}

/**
 * Turns the authenticated principal into the actor of a gate or a session: its organization, its person and, for an
 * agent token, its launch (the identity module binds the real one; the sessions module uses it too).
 */
interface GateActorResolver {
    /**
     * Resolves the caller.
     *
     * @param principal The authenticated principal.
     * @return The actor, or null when the credential belongs to no organization.
     */
    suspend fun resolve(principal: AuthenticatedPrincipal): GateActor?
}
