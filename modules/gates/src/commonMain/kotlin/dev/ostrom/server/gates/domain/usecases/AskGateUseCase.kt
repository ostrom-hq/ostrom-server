package dev.ostrom.server.gates.domain.usecases

import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.Evidence
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateCommands
import dev.ostrom.server.gates.domain.models.GatePush
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.GateView
import dev.ostrom.server.gates.domain.models.OutdatedLauncherException
import dev.ostrom.server.gates.domain.services.GateDigester
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.services.PullRequestHeads
import kotlin.uuid.Uuid

/**
 * What a hook or the MCP tool sends before a boundary action. Everything else (which gate applies, the head and base
 * of a pull request, who asks, whether git is redirected) is decided by the server.
 *
 * @property sessionId The agent session.
 * @property runId The workflow run.
 * @property repo The repository the session declared.
 * @property realRepo The repository the command runs in.
 * @property actionClass The action class.
 * @property tool The agent tool.
 * @property command The command.
 * @property harnessVersion The launcher or plugin version.
 * @property push The refs a push sends.
 * @property pullRequest The pull request number of a merge.
 * @property diff The diff a push sends.
 * @property via The path.
 */
data class AskGate(
    val sessionId: Uuid?,
    val runId: String?,
    val repo: String?,
    val realRepo: String?,
    val actionClass: String,
    val tool: String?,
    val command: String,
    val harnessVersion: String?,
    val push: GatePush?,
    val pullRequest: Int?,
    val diff: String?,
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
     * @throws OutdatedLauncherException When an enforced push comes without its commits.
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
        val asker = actor.personId ?: actor.id
        val repo = ask.repo?.trim()?.takeIf { it.isNotEmpty() }
        val realRepo = ask.realRepo?.trim()?.takeIf { it.isNotEmpty() && it != repo }
        val node = nodes.resolve(orgId, realRepo ?: repo, ask.actionClass)
        // The head and base of a merge come from GitHub, never from the caller (audit A7b, A8). Unknown: the merge is
        // bound to the command in this session, as the prototype did.
        val pullRequest = ask.pullRequest?.let { number -> repo?.let { pullRequests.head(orgId, it, number) } }
        val push = if (pullRequest == null) ask.push else null
        val command = ask.command.trim().take(MAX_COMMAND)
        // CORRECTIFS-3: a hook from before the commits were sent; its yes would be bound to the text, not the commits.
        if (node?.enforced == true && pullRequest == null && push == null && GateCommands.mentionsPush(command)) {
            throw OutdatedLauncherException()
        }
        val diff = ask.diff?.take(MAX_DIFF)
        val subject = GateSubject(
            repo = repo,
            realRepo = realRepo,
            actionClass = ask.actionClass,
            command = command,
            scope = "session:${ask.sessionId ?: "-"}",
            push = push,
            pullRequest = pullRequest,
            commits = pullRequest?.let { listOf(it.headSha) } ?: push?.refs?.map { it.sha }?.filter { it.isNotEmpty() }.orEmpty(),
            diffDigest = push?.let { digester.digest(diff.orEmpty()) },
            tool = ask.tool?.take(MAX_LABEL) ?: "Bash",
            harnessVersion = ask.harnessVersion?.take(MAX_LABEL),
            runId = ask.runId?.take(MAX_LABEL),
        )
        val evidence = buildList {
            node?.let { add(Evidence("Gate", "${it.name} (${it.workflowName})")) }
            add(Evidence("Command", command.take(MAX_SHOWN_COMMAND).ifEmpty { subject.tool }))
            push?.let { add(Evidence("Commits", "${pushLine(it)}. This yes covers these commits only.")) }
            (realRepo ?: repo)?.let { add(Evidence("Repository", it)) }
            if (realRepo != null && repo != null) {
                add(
                    Evidence(
                        "Session repository",
                        "$repo: NOT the repository this command runs in ($realRepo). Check before saying yes.",
                    ),
                )
            }
            val session = ask.sessionId?.let { " · session ${it.toString().take(SHORT_SESSION)}" }.orEmpty()
            val pr = pullRequest?.let { " · PR #${it.number} @ ${it.headSha.take(SHORT_SHA)} into ${it.base}" }.orEmpty()
            add(Evidence("Asked by", "$asker$session$pr"))
            subject.harnessVersion?.let { add(Evidence("Harness", it)) }
            if (push != null && !diff.isNullOrEmpty()) add(Evidence("Diff", diff))
        }
        return open(
            OpenGate(
                actor = actor,
                asker = asker,
                via = ask.via,
                sessionId = ask.sessionId,
                subject = subject,
                node = node,
                label = node?.name ?: ask.actionClass,
                evidence = evidence,
                redirected = GateCommands.redirected(command),
            ),
        )
    }

    private fun pushLine(push: GatePush): String {
        val refs = push.refs.joinToString(", ") { "${it.ref} ← ${it.sha.take(PUSH_SHA).ifEmpty { "(deleted)" }}" }
        val force = if (push.force) " (force-push)" else ""
        val remote = if (push.remote.isEmpty()) "" else " on ${push.remote}"
        return "$refs$remote$force"
    }

    private companion object {
        const val MAX_DIFF = 8_000
        const val MAX_COMMAND = 8_000
        const val MAX_LABEL = 200
        const val MAX_SHOWN_COMMAND = 400
        const val SHORT_SHA = 7
        const val PUSH_SHA = 12
        const val SHORT_SESSION = 8
    }
}
