// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.infrastructure

import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.CiCheck
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GatePullRequest
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.domain.services.CiChecks
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.services.GateChannel
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.services.GateNotifier
import dev.ostrom.server.gates.domain.services.GatePolicies
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.gates.domain.services.PullRequestHeads
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// The gates module's ports are owned by other modules (identity, orgs, sessions, receipts, compiler,
// integrations-github, notifications), which bind their implementation over these defaults. Each default fails
// closed: until the owner is loaded, nothing is approved that should not be.

/** No organization setting yet: no self-approval, a yes always comes from another person (S2-15). */
class DefaultGatePolicies : GatePolicies {
    override suspend fun selfApproval(orgId: Uuid): SelfApprovalPolicy = SelfApprovalPolicy.DEFAULT
}

/** Without the sessions module, a gate asked inside a session is refused rather than added to an unknown session. */
class UnavailableGateSessions : GateSessions {
    override suspend fun assertOpen(orgId: Uuid, sessionId: String, launchId: String?) {
        throw OstromException(ErrorKind.UNAVAILABLE, "Agent sessions are not available on this server.")
    }
}

/** Without compiled workflows, no gate node applies: the action is recorded as ungated. */
class NoGateNodes : GateNodeResolver {
    override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String): GateNode? = null
}

/** Without the GitHub integration, a pull request's head is unknown: the merge is bound to its command. */
class UnknownPullRequestHeads : PullRequestHeads {
    override suspend fun head(orgId: Uuid, repo: String, number: Int): GatePullRequest? = null
}

/** Without the GitHub integration, no CI run is known: a check node stays the workstation's declaration. */
class NoCiChecks : CiChecks {
    override suspend fun latest(orgId: Uuid, repo: String, sha: String, name: String): CiCheck? = null
}

/**
 * Tells every bound [GateChannel] at once, each within [CHANNEL_TIMEOUT]: a slow upstream holds an ask or an answer
 * one timeout at most. A channel that fails or times out is logged and skipped: the request is stored and visible in
 * the console, and the other channels still hear of it. Without any channel, approvers find
 * their requests in the console and the hooks poll the gate.
 *
 * @param channels The channels, read at the first event (the integrations bind theirs after this module).
 * @param logger Logs a failing channel.
 */
class ChannelsGateNotifier(channels: () -> List<GateChannel>, private val logger: Logger) : GateNotifier {
    private val channels by lazy(channels)

    override suspend fun requested(gate: Gate, requests: List<ApprovalRequest>) =
        each(gate) { it.requested(gate, requests) }

    override suspend fun decided(gate: Gate, requests: List<ApprovalRequest>) =
        each(gate) { it.decided(gate, requests) }

    // `async`, not `launch`: a channel's own cancellation reaches the caller through `awaitAll`.
    private suspend fun each(gate: Gate, event: suspend (GateChannel) -> Unit) {
        coroutineScope { channels.map { channel -> async { tell(gate, channel, event) } }.awaitAll() }
    }

    private suspend fun tell(gate: Gate, channel: GateChannel, event: suspend (GateChannel) -> Unit) {
        val fields = mapOf("channel" to channel.name, "gate_id" to gate.id)
        try {
            withTimeout(CHANNEL_TIMEOUT) { event(channel) }
        } catch (error: TimeoutCancellationException) {
            logger.warn("gate channel timed out", fields, error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("gate channel failed", fields, error)
        }
    }

    /** The notifier's constants. */
    companion object {
        /** How long one channel may take to hear of a gate event. */
        val CHANNEL_TIMEOUT: Duration = 5.seconds
    }
}

/** Without the identity module, no credential belongs to an organization. */
class NoGateActors : GateActorResolver {
    override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? = null
}
