// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.usecases

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.ApprovalRequest
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Evidence
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateKeys
import dev.ostrom.server.gates.domain.models.GateMode
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.models.GateWithoutApproverException
import dev.ostrom.server.gates.domain.models.InvalidGateRequestException
import dev.ostrom.server.gates.domain.models.NotYourGateException
import dev.ostrom.server.gates.domain.models.PushRef
import dev.ostrom.server.gates.domain.models.RedirectedPushException
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.models.SelfApproval
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.gates.domain.repositories.GatesTransaction
import dev.ostrom.server.gates.domain.services.GateDigester
import dev.ostrom.server.gates.domain.services.GateNotifier
import dev.ostrom.server.gates.domain.services.GatePolicies
import dev.ostrom.server.gates.domain.services.GateSessions
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A request to open a gate, as the hook (PreToolUse), the GitHub check or the MCP tool sends it, once the presentation
 * layer has resolved the caller and the server-side facts (pull request head from GitHub, gate node of the workflow).
 *
 * @property actor Who calls.
 * @property asker The person the action is asked for: the developer of the session, or the pull request author.
 * @property via The path that asks.
 * @property sessionId The agent session, when the hook or MCP asks.
 * @property subject What the yes would cover.
 * @property node The gate node that applies, or null when none does.
 * @property label The action label.
 * @property evidence What the approvers are shown.
 * @property redirected True when the command rewrites where git reads or sends (audit A6).
 */
data class OpenGate(
    val actor: GateActor,
    val asker: String,
    val via: AskedVia,
    val sessionId: String?,
    val subject: GateSubject,
    val node: GateNode?,
    val label: String,
    val evidence: List<Evidence>,
    val redirected: Boolean = false,
)

/** Opens a gate, or joins the live one for the same action. */
interface OpenGateUseCase {
    /**
     * Opens or joins a gate. An approved yes stays valid until the action runs (reserve, then consume); a no holds for
     * the scope (asking again does not disturb the approvers again).
     *
     * @param request What is asked.
     * @return The gate and who it waits for.
     * @throws RedirectedPushException When a gated push redirects git.
     * @throws GateWithoutApproverException When the enforced gate has nobody to say yes.
     * @throws NotYourGateException When an agent token asks for another session.
     * @throws InvalidGateRequestException When a field is out of bounds.
     */
    suspend operator fun invoke(request: OpenGate): GateView
}

/**
 * [OpenGateUseCase] on the repository: one transaction per request, serialized on the gate key.
 *
 * @param repository The gates.
 * @param sessions Guards closed sessions.
 * @param policies The organization's self-approval setting.
 * @param notifier Tells the approvers; it never throws.
 * @param digester Hashes keys.
 * @param clock The clock.
 */
class OpenGateUseCaseImpl(
    private val repository: GatesRepository,
    private val sessions: GateSessions,
    private val policies: GatePolicies,
    private val notifier: GateNotifier,
    private val digester: GateDigester,
    private val clock: Clock,
) : OpenGateUseCase {
    override suspend fun invoke(request: OpenGate): GateView {
        validate(request)
        val orgId = request.actor.orgId
        request.sessionId?.let { sessions.assertOpen(orgId, it, request.actor.launchId) }
        val node = request.node
        if (request.subject.push != null && node?.enforced == true && request.redirected) {
            throw RedirectedPushException()
        }
        val (mode, approvers) = modeOf(node)
        if (mode == GateMode.ENFORCED && approvers.isEmpty()) throw GateWithoutApproverException()
        val key = digester.digest(GateKeys.keyPreimage(orgId.toString(), request.subject, node))
        val opened = repository.transaction {
            lockKey(orgId, key)
            val same = byKey(orgId, key, SAME_KEY_LIMIT)
                .filter { request.subject.push == null || it.subject.diffDigest == request.subject.diffDigest }
            joined(request, same, mode) ?: created(newGate(request, key, mode), approvers)
        }
        if (opened.created && opened.gate.mode == GateMode.ENFORCED) notifier.requested(opened.gate, opened.requests)
        return viewOf(opened.gate, opened.requests, policies.selfApproval(orgId))
    }

    // The live gate of the same action takes the new asker; a refused one answers again. An expired yes is not
    // joined: the same action asks a new one (S2-29). A shadow or ungated record is joined in its own mode only.
    private suspend fun GatesTransaction.joined(request: OpenGate, same: List<Gate>, mode: GateMode): Opened? {
        val now = clock.now()
        val live = same.firstOrNull { it.joinable(mode, now) }
        val refused = same.firstOrNull()?.takeIf { it.status == GateStatus.REJECTED }
        return when {
            live != null && live.status == GateStatus.APPROVED && request.via != AskedVia.CI ->
                taken(request, live, mode)
            live != null -> {
                val joined = join(live, request)
                // Only the askers: an answer, reserve or consume may have committed since `byKey` read the row.
                if (joined != live) addAskers(joined)
                Opened(joined, requestsOf(joined.orgId, joined.id), created = false)
            }
            refused != null -> Opened(refused, requestsOf(refused.orgId, refused.id), created = false)
            else -> null
        }
    }

    // Second audit H1: the ask that finds a yes takes it. Under the row lock the gate is used up, this ask is answered
    // approved, and the next ask of the same action opens a new gate: one yes, one action. The GitHub check, which
    // shares a merge's gate, only reads it. A gate that moved since `byKey` read it is joined as it is now.
    private suspend fun GatesTransaction.taken(request: OpenGate, live: Gate, mode: GateMode): Opened? {
        val fresh = lockGate(live.orgId, live.id)
        if (fresh == null || fresh.status != GateStatus.APPROVED) return joined(request, listOfNotNull(fresh), mode)
        val joined = join(fresh, request)
        updateGate(joined.copy(status = GateStatus.CONSUMED, consumedAt = clock.now()))
        return Opened(joined, requestsOf(joined.orgId, joined.id), created = false)
    }

    private fun Gate.joinable(mode: GateMode, now: Instant): Boolean = when (status) {
        GateStatus.SHADOW, GateStatus.UNGATED -> this.mode == mode
        in LIVE -> !yesExpired(now) && !reservationLapsed(now)
        else -> false
    }

    private suspend fun GatesTransaction.created(gate: Gate, approvers: List<String>): Opened {
        val now = gate.createdAt
        val requests = approvers.map { approver ->
            ApprovalRequest(Uuid.random(), gate.id, gate.orgId, approver, RequestStatus.PENDING, createdAt = now)
        }
        insertGate(gate)
        if (requests.isNotEmpty()) insertRequests(requests)
        return Opened(gate, requests, created = true)
    }

    private fun modeOf(node: GateNode?): Pair<GateMode, List<String>> = when {
        node == null -> GateMode.UNGATED to emptyList()
        !node.enforced -> GateMode.SHADOW to emptyList()
        // Each approver once, whatever their case.
        else -> GateMode.ENFORCED to node.approvers.fold(emptyList<String>()) { all, approver ->
            SelfApproval.withAskers(all, approver)
        }
    }

    private fun newGate(request: OpenGate, key: String, mode: GateMode) = Gate(
        id = Uuid.random(),
        orgId = request.actor.orgId,
        key = key,
        subject = request.subject,
        node = request.node,
        label = request.label,
        mode = mode,
        status = when (mode) {
            GateMode.ENFORCED -> GateStatus.PENDING
            GateMode.SHADOW -> GateStatus.SHADOW
            GateMode.UNGATED -> GateStatus.UNGATED
        },
        askedBy = SelfApproval.withAskers(emptyList(), request.asker),
        askedVia = setOf(request.via),
        sessionIds = listOfNotNull(request.sessionId),
        launchIds = listOfNotNull(request.actor.launchId),
        shownDigest = digester.digest(GateKeys.shownPreimage(request.label, request.subject, request.evidence)),
        evidence = request.evidence,
        createdAt = clock.now(),
    )

    private fun join(gate: Gate, request: OpenGate): Gate = gate.copy(
        askedBy = SelfApproval.withAskers(gate.askedBy, request.asker),
        askedVia = gate.askedVia + request.via,
        sessionIds = (gate.sessionIds + listOfNotNull(request.sessionId)).distinct(),
        launchIds = (gate.launchIds + listOfNotNull(request.actor.launchId)).distinct(),
    )

    private fun validate(request: OpenGate) {
        val actor = request.actor
        // An agent token asks only inside a session; the sessions module checks the session is of its launch.
        if (actor.kind == ActorKind.AGENT_SESSION && (request.sessionId == null || actor.launchId == null)) {
            throw NotYourGateException()
        }
        request.sessionId?.let { id ->
            ensure(id.isNotBlank() && id.length <= MAX_SESSION_ID) {
                "A session id has 1 to $MAX_SESSION_ID characters."
            }
        }
        val subject = request.subject
        ensure(subject.command.length <= MAX_COMMAND) { "The command is longer than $MAX_COMMAND characters." }
        ensure(request.evidence.size <= MAX_EVIDENCE) { "At most $MAX_EVIDENCE evidence lines." }
        ensure(request.evidence.all { it.label.length <= MAX_LABEL && it.value.length <= MAX_VALUE }) {
            "An evidence line is too long."
        }
        ensure(request.label.length <= MAX_LABEL) { "The label is longer than $MAX_LABEL characters." }
        ensure(subject.commits.size <= MAX_COMMITS && subject.commits.all(::isSha)) { "Invalid commits." }
        subject.pullRequest?.let { ensure(isSha(it.headSha) && it.base.length <= MAX_REF) { "Invalid pull request." } }
        subject.push?.let { push ->
            ensure(push.refs.isNotEmpty() && push.refs.size <= MAX_REFS) { "A push names 1 to $MAX_REFS refs." }
            ensure(push.remote.length <= MAX_REF) { "The remote is too long." }
            ensure(push.refs.all(::isValidRef)) { "Invalid push ref." }
        }
    }

    private fun isValidRef(ref: PushRef): Boolean =
        ref.ref.isNotBlank() && ref.ref.length <= MAX_REF && (ref.sha.isEmpty() || isSha(ref.sha))

    private inline fun ensure(condition: Boolean, reason: () -> String) {
        if (!condition) throw InvalidGateRequestException(reason())
    }

    private data class Opened(val gate: Gate, val requests: List<ApprovalRequest>, val created: Boolean)

    private companion object {
        const val SAME_KEY_LIMIT = 20
        const val MAX_SESSION_ID = 128
        const val MAX_COMMAND = 8_000
        const val MAX_EVIDENCE = 30
        const val MAX_LABEL = 200
        const val MAX_VALUE = 8_000
        const val MAX_COMMITS = 250
        const val MAX_REFS = 50
        const val MAX_REF = 200
    }
}

// Top-level, not in the companion: a private companion property has a getter nothing calls.
private val LIVE = setOf(GateStatus.PENDING, GateStatus.APPROVED, GateStatus.RESERVED)

/**
 * True for a full commit id (SHA-1 or SHA-256, lower-case hex).
 *
 * @param value The text.
 * @return Whether it is one.
 */
internal fun isSha(value: String): Boolean =
    (value.length == SHA1_LENGTH || value.length == SHA256_LENGTH) && value.all { it in '0'..'9' || it in 'a'..'f' }

private const val SHA1_LENGTH = 40
private const val SHA256_LENGTH = 64

/**
 * The caller's view of a gate.
 *
 * @param gate The gate.
 * @param requests Its requests.
 * @param policy The organization's self-approval setting.
 * @return The view, with who the gate still waits for.
 */
internal fun viewOf(gate: Gate, requests: List<ApprovalRequest>, policy: SelfApprovalPolicy): GateView {
    val waiting = if (gate.status == GateStatus.PENDING) {
        val pending = requests.filter { it.status == RequestStatus.PENDING }.map { it.approverId }
        SelfApproval.waitingFor(gate.askedBy, pending, policy)
    } else {
        emptyList()
    }
    return GateView(gate, requests, waiting)
}
