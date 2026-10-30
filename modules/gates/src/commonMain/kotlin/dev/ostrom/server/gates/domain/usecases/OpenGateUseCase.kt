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
import dev.ostrom.server.gates.domain.models.RedirectedPushException
import dev.ostrom.server.gates.domain.models.RequestStatus
import dev.ostrom.server.gates.domain.models.SelfApproval
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.gates.domain.services.GateDigester
import dev.ostrom.server.gates.domain.services.GateNotifier
import dev.ostrom.server.gates.domain.services.GatePolicies
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
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
    val sessionId: Uuid?,
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
 * @param notifier Tells the approvers.
 * @param digester Hashes keys.
 * @param clock The clock.
 * @param logger Logs notification failures.
 */
class OpenGateUseCaseImpl(
    private val repository: GatesRepository,
    private val sessions: GateSessions,
    private val policies: GatePolicies,
    private val notifier: GateNotifier,
    private val digester: GateDigester,
    private val clock: Clock,
    private val logger: Logger,
) : OpenGateUseCase {
    override suspend fun invoke(request: OpenGate): GateView {
        validate(request)
        val orgId = request.actor.orgId
        request.sessionId?.let { sessions.assertOpen(orgId, it) }
        val node = request.node
        if (request.subject.push != null && node?.enforced == true && request.redirected) throw RedirectedPushException()
        val (mode, approvers) = when {
            node == null -> GateMode.UNGATED to emptyList()
            !node.enforced -> GateMode.SHADOW to emptyList()
            else -> GateMode.ENFORCED to SelfApproval.withAskers(emptyList(), *node.approvers.toTypedArray())
        }
        if (mode == GateMode.ENFORCED && approvers.isEmpty()) throw GateWithoutApproverException()
        val key = digester.digest(GateKeys.keyPreimage(orgId.toString(), request.subject, node))
        val shown = digester.digest(GateKeys.shownPreimage(request.label, request.subject, request.evidence))

        val opened = repository.transaction {
            lockKey(orgId, key)
            val same = byKey(orgId, key, SAME_KEY_LIMIT)
                .filter { request.subject.push == null || it.subject.diffDigest == request.subject.diffDigest }
            val live = same.firstOrNull { it.status in LIVE }
            if (live != null) {
                val joined = join(live, request)
                if (joined != live) updateGate(joined)
                return@transaction Opened(joined, requestsOf(orgId, joined.id), created = false)
            }
            val latest = same.firstOrNull()
            if (latest?.status == GateStatus.REJECTED) {
                return@transaction Opened(latest, requestsOf(orgId, latest.id), created = false)
            }
            val now = clock.now()
            val gate = Gate(
                id = Uuid.random(),
                orgId = orgId,
                key = key,
                subject = request.subject,
                node = node,
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
                shownDigest = shown,
                evidence = request.evidence,
                createdAt = now,
            )
            val requests = approvers.map { approver ->
                ApprovalRequest(Uuid.random(), gate.id, orgId, approver, RequestStatus.PENDING, createdAt = now)
            }
            insertGate(gate)
            if (requests.isNotEmpty()) insertRequests(requests)
            Opened(gate, requests, created = true)
        }

        if (opened.created && opened.gate.mode == GateMode.ENFORCED) {
            try {
                notifier.requested(opened.gate, opened.requests)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The request is stored and visible in the console: a failed notification never loses it.
                logger.warn("gate notification failed", mapOf("gate_id" to opened.gate.id), error)
            }
        }
        return viewOf(opened.gate, opened.requests, policies.selfApproval(orgId))
    }

    private fun join(gate: Gate, request: OpenGate): Gate = gate.copy(
        askedBy = SelfApproval.withAskers(gate.askedBy, request.asker),
        askedVia = gate.askedVia + request.via,
        sessionIds = request.sessionId?.takeIf { it !in gate.sessionIds }?.let { gate.sessionIds + it }
            ?: gate.sessionIds,
    )

    private fun validate(request: OpenGate) {
        val actor = request.actor
        if (actor.kind == ActorKind.AGENT_SESSION && (request.sessionId == null || request.sessionId != actor.sessionId)) {
            throw NotYourGateException()
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
            ensure(push.refs.all { it.ref.isNotBlank() && it.ref.length <= MAX_REF && isSha(it.sha) }) {
                "Invalid push ref."
            }
        }
    }

    private inline fun ensure(condition: Boolean, reason: () -> String) {
        if (!condition) throw InvalidGateRequestException(reason())
    }

    private data class Opened(val gate: Gate, val requests: List<ApprovalRequest>, val created: Boolean)

    private companion object {
        val LIVE = setOf(GateStatus.PENDING, GateStatus.APPROVED, GateStatus.RESERVED)
        const val SAME_KEY_LIMIT = 20
        const val MAX_COMMAND = 8_000
        const val MAX_EVIDENCE = 30
        const val MAX_LABEL = 200
        const val MAX_VALUE = 8_000
        const val MAX_COMMITS = 250
        const val MAX_REFS = 50
        const val MAX_REF = 200
    }
}

/**
 * True for a full commit id (SHA-1 or SHA-256, lower-case hex).
 *
 * @param value The text.
 * @return Whether it is one.
 */
internal fun isSha(value: String): Boolean =
    (value.length == 40 || value.length == 64) && value.all { it in '0'..'9' || it in 'a'..'f' }

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
