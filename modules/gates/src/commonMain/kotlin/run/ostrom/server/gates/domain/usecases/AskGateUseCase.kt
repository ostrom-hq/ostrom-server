// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.gates.domain.usecases

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.AskedVia
import run.ostrom.server.gates.domain.models.Evidence
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateCommands
import run.ostrom.server.gates.domain.models.GateNode
import run.ostrom.server.gates.domain.models.GatePullRequest
import run.ostrom.server.gates.domain.models.GatePush
import run.ostrom.server.gates.domain.models.GateSubject
import run.ostrom.server.gates.domain.models.GateView
import run.ostrom.server.gates.domain.models.GitHubRequiredException
import run.ostrom.server.gates.domain.models.InvalidGateRequestException
import run.ostrom.server.gates.domain.models.MissingPullRequestException
import run.ostrom.server.gates.domain.models.NoPersonException
import run.ostrom.server.gates.domain.models.OutdatedLauncherException
import run.ostrom.server.gates.domain.services.GateDigester
import run.ostrom.server.gates.domain.services.GateNodeResolver
import run.ostrom.server.gates.domain.services.PullRequestHeads
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
 * @property unlinkedAuthor The GitHub account id of a pull request's author linked to no Ostrom person ([Gate]).
 */
data class AskGate(
    val sessionId: String?,
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
    val unlinkedAuthor: Long? = null,
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
        // S2-15: a gate is asked for a person, never for a credential id that self-approval could not recognize.
        val asker = actor.personId ?: throw NoPersonException()
        // `?.trim()?.takeIf` has a null check nothing reaches: Kover counts it, so the texts are read as non-null.
        val repo = ask.repo.orEmpty().trim().ifEmpty { null }
        val realRepo = ask.realRepo.orEmpty().trim().takeIf { it.isNotEmpty() && it != repo }
        // Never cut: a tail the checks did not read would run unseen. OpenGate refuses a command that is too long.
        val command = ask.command.trim()
        val (actionClass, node) = gateOf(orgId, realRepo ?: repo, ask, command)
        val pullRequest = pullRequestOf(orgId, repo, ask, command, node?.enforced == true)
        // Audit S2-05: the hook's force flag is the machine's word; the command's options count too.
        val push = ask.push.takeIf { pullRequest == null }
            ?.let { it.copy(force = it.force || GateCommands.forces(command)) }
        // CORRECTIFS-3: a hook from before the commits were sent; its yes would be bound to the text, not the commits.
        val bound = pullRequest != null || push != null
        if (node?.enforced == true && !bound && GateCommands.mentionsPush(command)) throw OutdatedLauncherException()
        val subject = subjectOf(ask.copy(actionClass = actionClass), command, repo to realRepo, pullRequest, push)
        return open(
            OpenGate(
                actor = actor,
                asker = asker,
                // Second audit L2: the GitHub check is a service; anyone else saying `ci` asks as a hook.
                via = if (ask.via == AskedVia.CI && actor.kind != ActorKind.SERVICE) AskedVia.HOOK else ask.via,
                sessionId = ask.sessionId,
                subject = subject,
                node = node,
                label = if (node == null) actionClass else node.name,
                evidence = evidenceOf(node, subject, asker, ask.sessionId, ask.diff?.take(MAX_DIFF)),
                redirected = GateCommands.redirected(command),
                // Only the GitHub check knows a pull request's author: a hook never names one.
                unlinkedAuthor = ask.unlinkedAuthor.takeIf { actor.kind == ActorKind.SERVICE },
            ),
        )
    }

    // Second audit H3: the class is the hook's word, read from its patterns, and the hook asks one class per command.
    // What the server reads itself also asks: refs reaching main or master (a push to main), a tag (`refs/tags/…`, or
    // a short name shaped as a version: the CLI sends short names), a merge the command shows. The claimed class keeps
    // its gate when it is enforced; else the first enforced gate of what was read is taken. A push-main or push-tag ask
    // whose refs hold no such ref is no such push, so their patterns may catch every push and let the refs decide.
    private suspend fun gateOf(orgId: Uuid, repo: String?, ask: AskGate, command: String): Pair<String, GateNode?> {
        val refs = ask.push?.refs.orEmpty().map { it.ref }
        val read = buildList {
            if (refs.any { it.removePrefix(HEADS) in MAIN_BRANCHES }) add(PUSH_MAIN)
            if (refs.any(::isTag)) add(PUSH_TAG)
            if (GateCommands.mentionsMerge(command)) add(MERGE)
        }
        val notSo = ask.push != null && ask.actionClass in BY_REFS && ask.actionClass !in read
        // One read of the gates for every class this ask looks up (each read compiles the workflows and the rules).
        val gateOf = nodes.resolver(orgId, repo)
        val claimed = if (notSo) null else gateOf(ask.actionClass)
        val backstop = if (claimed?.enforced == true) {
            null
        } else {
            read.filter { it != ask.actionClass }.firstNotNullOfOrNull { cls ->
                gateOf(cls)?.takeIf { it.enforced }?.let { cls to it }
            }
        }
        return backstop ?: (ask.actionClass to claimed)
    }

    private fun subjectOf(
        ask: AskGate,
        command: String,
        repos: Pair<String?, String?>,
        pullRequest: GatePullRequest?,
        push: GatePush?,
    ): GateSubject = GateSubject(
        repo = repos.first,
        realRepo = repos.second,
        actionClass = ask.actionClass,
        command = command,
        scope = "session:${ask.sessionId ?: "-"}",
        push = push,
        pullRequest = pullRequest,
        commits = when {
            pullRequest != null -> listOf(pullRequest.headSha)
            push != null -> push.refs.map { it.sha }.filter { it.isNotEmpty() }
            else -> emptyList()
        },
        diffDigest = push?.let { digester.digest(ask.diff?.take(MAX_DIFF).orEmpty()) },
        tool = (ask.tool ?: "Bash").take(MAX_LABEL),
        harnessVersion = ask.harnessVersion?.take(MAX_LABEL),
        runId = ask.runId?.take(MAX_LABEL),
    )

    // What the approvers are shown, all computed here: the gate, the command, the commits, where it runs, who asks.
    private fun evidenceOf(
        node: GateNode?,
        subject: GateSubject,
        asker: String,
        sessionId: String?,
        diff: String?,
    ): List<Evidence> = buildList {
        val repo = subject.repo
        val realRepo = subject.realRepo
        val push = subject.push
        node?.let { add(Evidence("Gate", "${it.name} (${it.workflowName})")) }
        add(Evidence("Command", subject.command.take(MAX_SHOWN_COMMAND).ifEmpty { subject.tool }))
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
        val session = sessionId?.let { " · session ${it.take(SHORT_SESSION)}" }.orEmpty()
        val pr = subject.pullRequest?.let { " · PR #${it.number} @ ${it.headSha.take(SHORT_SHA)} into ${it.base}" }
            .orEmpty()
        add(Evidence("Asked by", "$asker$session$pr"))
        subject.harnessVersion?.let { add(Evidence("Harness", it)) }
        // Audit S2-05: before the push, GitHub does not have these commits; the diff is what the machine sent.
        if (push != null && !diff.isNullOrEmpty()) add(Evidence(MACHINE_DIFF, diff))
    }

    // The head and base of a merge come from GitHub, never from the caller (audit A7b, A8). An enforced merge GitHub
    // does not describe is refused, never bound to the command's text (audit S2-06); a shadow one is only logged.
    private suspend fun pullRequestOf(
        orgId: Uuid,
        repo: String?,
        ask: AskGate,
        command: String,
        enforced: Boolean,
    ): GatePullRequest? {
        val number = ask.pullRequest
        val head = when {
            number == null -> null
            repo == null -> throw InvalidGateRequestException("A pull request needs the session's repository.")
            else -> pullRequests.head(orgId, repo, number)
        }
        val refusal = when {
            !enforced || head != null -> null
            number != null -> GitHubRequiredException()
            GateCommands.mentionsMerge(command) -> MissingPullRequestException()
            else -> null
        }
        refusal?.let { throw it }
        return head
    }

    private fun pushLine(push: GatePush): String {
        val refs = push.refs.joinToString(", ") { "${it.ref} ← ${it.sha.take(PUSH_SHA).ifEmpty { "(deleted)" }}" }
        val force = if (push.force) " (force-push)" else ""
        val remote = if (push.remote.isEmpty()) "" else " on ${push.remote}"
        return "$refs$remote$force"
    }

    private companion object {
        const val MACHINE_DIFF = "Diff (sent by the developer's machine, not checked by Ostrom)"
        const val MAX_DIFF = 8_000
        const val MAX_LABEL = 200
        const val MAX_SHOWN_COMMAND = 400
        const val SHORT_SHA = 7
        const val PUSH_SHA = 12
        const val SHORT_SESSION = 8
        const val PUSH_MAIN = "push-main"
        const val MERGE = "merge"
        const val HEADS = "refs/heads/"
        const val PUSH_TAG = "push-tag"
    }
}

// Top-level, not in the companion: a private companion property has a getter nothing calls.
private val MAIN_BRANCHES = setOf("main", "master")

// The classes a push's refs prove or disprove.
private val BY_REFS = setOf("push-main", "push-tag")

// `v1.4`, `1.4.0`, `v2.0.0-rc.1`: the release tags the built-in catalogue knows.
private val VERSION = Regex("^v?\\d+(\\.\\d+)+([-+.][\\w.]+)?$")

private fun isTag(ref: String): Boolean =
    ref.startsWith("refs/tags/") || !ref.startsWith("refs/") && VERSION.matches(ref)
