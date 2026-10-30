package dev.ostrom.server.gates.domain.usecases

import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Evidence
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GatePush
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.services.GateDigester
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.services.PullRequestHeads
import kotlin.uuid.Uuid

/**
 * What the hook or the MCP tool sends before a boundary action. Everything else (which gate applies, the head and base
 * of a pull request, what the approvers are shown) is decided by the server.
 *
 * @property sessionId The agent session.
 * @property repo The repository the session declared.
 * @property realRepo The repository the command runs in.
 * @property actionClass The action class.
 * @property command The command.
 * @property push The refs a push sends.
 * @property pullRequest The pull request number of a merge.
 * @property commits The commits a push sends.
 * @property diff The diff a push sends.
 * @property redirected True when git is redirected.
 * @property via The path.
 */
data class AskGate(
    val sessionId: Uuid?,
    val repo: String?,
    val realRepo: String?,
    val actionClass: String,
    val command: String,
    val push: GatePush?,
    val pullRequest: Int?,
    val commits: List<String>,
    val diff: String?,
    val redirected: Boolean,
    val via: AskedVia,
)

/** Turns what a hook asks into a gate: resolves the gate node, the pull request and what is shown, then opens it. */
interface AskGateUseCase {
    /**
     * Asks for a gate.
     *
     * @param actor The device or agent token that asks.
     * @param ask What it asks.
     * @return The gate.
     */
    suspend operator fun invoke(actor: GateActor, ask: AskGate): GateView
}

/**
 * [AskGateUseCase] on the workflows and GitHub.
 *
 * @param open Opens the gate.
 * @param nodes Finds the gate node that covers the action.
 * @param pullRequests Reads a pull request's head and base from GitHub.
 * @param digester Hashes the diff.
 */
class AskGateUseCaseImpl(
    private val open: OpenGateUseCase,
    private val nodes: GateNodeResolver,
    private val pullRequests: PullRequestHeads,
    private val digester: GateDigester,
) : AskGateUseCase {
    override suspend fun invoke(actor: GateActor, ask: AskGate): GateView {
        val orgId = actor.orgId
        val repo = ask.repo?.trim()?.takeIf { it.isNotEmpty() }
        val realRepo = ask.realRepo?.trim()?.takeIf { it.isNotEmpty() && it != repo }
        val node = nodes.resolve(orgId, repo, ask.actionClass)
        // The head and base of a merge come from GitHub, never from the caller (audit A7b, A8). Unknown: the merge is
        // bound to the command in this session, as the prototype did, and may ask a second yes on the GitHub check.
        val pullRequest = ask.pullRequest?.let { number -> repo?.let { pullRequests.head(orgId, it, number) } }
        val push = if (pullRequest == null) ask.push else null
        val diff = ask.diff?.take(MAX_DIFF)
        val command = ask.command.take(MAX_COMMAND)
        val subject = GateSubject(
            repo = repo,
            realRepo = realRepo,
            actionClass = ask.actionClass,
            command = command,
            scope = "session:${ask.sessionId ?: "-"}",
            push = push,
            pullRequest = pullRequest,
            commits = pullRequest?.let { listOf(it.headSha) } ?: ask.commits,
            diffDigest = if (push != null) digester.digest(diff.orEmpty()) else null,
        )
        val evidence = buildList {
            node?.let { add(Evidence("Gate", it.name)) }
            if (command.isNotEmpty()) add(Evidence("Command", command.take(MAX_SHOWN_COMMAND)))
            add(Evidence("Repository", realRepo ?: repo ?: "none"))
            if (realRepo != null && repo != null) {
                add(Evidence("Session repository", "$repo: NOT the repository this command runs in. Check before saying yes."))
            }
            pullRequest?.let { add(Evidence("Pull request", "#${it.number} @ ${it.headSha.take(SHORT_SHA)} into ${it.base}")) }
            push?.let { p ->
                val refs = p.refs.joinToString(", ") { "${it.ref} ← ${it.sha.take(SHORT_SHA)}" }
                add(Evidence("Commits", "$refs${if (p.force) " (force-push)" else ""}. This yes covers these commits only."))
            }
            if (push != null && diff != null) add(Evidence("Diff", diff))
        }
        return open(
            OpenGate(
                actor = actor,
                asker = actor.personId ?: actor.id,
                via = ask.via,
                sessionId = ask.sessionId,
                subject = subject,
                node = node,
                label = node?.name ?: ask.actionClass,
                evidence = evidence,
                redirected = ask.redirected,
            ),
        )
    }

    private companion object {
        const val MAX_DIFF = 8_000
        const val MAX_COMMAND = 8_000
        const val MAX_SHOWN_COMMAND = 400
        const val SHORT_SHA = 7
    }
}
