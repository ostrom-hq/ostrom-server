package dev.ostrom.server.gates.domain.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The preimages of a gate's [Gate.key] and [Gate.shownDigest]: deterministic JSON (fixed key order), hashed by the
 * caller. Each one lists exactly what a yes is bound to, and nothing the agent controls without it being shown.
 */
object GateKeys {
    /**
     * What a yes covers.
     *
     * - A pull request merge: the repository, the head commit, the base branch and the gate node, but not the command
     *   nor the path that asked: the agent's `gh pr merge` and the GitHub check share one request (APPROBATION). The
     *   base and the node are in it since the audit of 30/10 (A8): a yes for `develop` never turns `main` green.
     * - A push: the repositories, the action class, the remote, every ref with its commit (sorted), the scope and the
     *   force flag (audit A5: a yes on a push never covers a force-push of the same commit). Not the command text: a
     *   piped `git push origin main 2>&1 | tail` is the same push.
     * - Anything else: the repository, the class, the normalized command and the scope.
     *
     * @param orgId The organization, so that two organizations never share a gate.
     * @param subject What is asked.
     * @param node The gate node, or null when no workflow gates the action.
     * @return The canonical text to hash.
     */
    fun keyPreimage(orgId: String, subject: GateSubject, node: GateNode?): String {
        val nodeRef = node?.let { JsonPrimitive("${it.workflowId}/${it.nodeId}") } ?: JsonNull
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "org" to JsonPrimitive(orgId),
            "repo" to text(subject.repo),
        )
        val pullRequest = subject.pullRequest
        val push = subject.push
        when {
            pullRequest != null -> {
                fields["action"] = JsonPrimitive("merge-pr")
                fields["sha"] = JsonPrimitive(pullRequest.headSha)
                fields["base"] = JsonPrimitive(pullRequest.base)
                fields["gate"] = nodeRef
            }
            push != null -> {
                fields["realRepo"] = text(subject.realRepo)
                fields["class"] = JsonPrimitive(subject.actionClass)
                fields["action"] = JsonPrimitive("push")
                fields["remote"] = JsonPrimitive(push.remote)
                fields["refs"] = JsonArray(push.refs.map { "${it.ref}=${it.sha}" }.sorted().map(::JsonPrimitive))
                fields["scope"] = JsonPrimitive(subject.scope)
                fields["force"] = JsonPrimitive(push.force)
            }
            else -> {
                fields["class"] = JsonPrimitive(subject.actionClass)
                fields["command"] = JsonPrimitive(normalizeCommand(subject.command))
                fields["scope"] = JsonPrimitive(subject.scope)
            }
        }
        return JsonObject(fields).toString()
    }

    /**
     * What the approvers are shown: the gate's label, its subject and its evidence. An approver's yes names this
     * digest; when it no longer matches, the yes is refused ([GateChangedException]). Who asked is not part of it:
     * a second path joining the request does not invalidate an answer in progress.
     *
     * @param label The action label.
     * @param subject What the yes covers.
     * @param evidence What is shown.
     * @return The canonical text to hash.
     */
    fun shownPreimage(label: String, subject: GateSubject, evidence: List<Evidence>): String {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "label" to JsonPrimitive(label),
            "repo" to text(subject.repo),
            "realRepo" to text(subject.realRepo),
            "class" to JsonPrimitive(subject.actionClass),
            "command" to JsonPrimitive(normalizeCommand(subject.command)),
            "commits" to JsonArray(subject.commits.map(::JsonPrimitive)),
            "diff" to text(subject.diffDigest),
            "pr" to (
                subject.pullRequest?.let {
                    JsonPrimitive("#${it.number}@${it.headSha}->${it.base}")
                } ?: JsonNull
                ),
            "push" to (
                subject.push?.let { push ->
                    JsonPrimitive(
                        push.refs.joinToString(",") { "${it.ref}=${it.sha}" } +
                            "@" + push.remote + if (push.force) " force" else "",
                    )
                } ?: JsonNull
                ),
            "evidence" to JsonArray(evidence.map { JsonPrimitive("${it.label}: ${it.value}") }),
        )
        return JsonObject(fields).toString()
    }

    /**
     * Collapses whitespace, so that the same command typed twice is the same action.
     *
     * @param command The command.
     * @return The command with runs of whitespace replaced by one space, trimmed.
     */
    fun normalizeCommand(command: String): String = command.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        .joinToString(" ")

    private fun text(value: String?) = value?.let(::JsonPrimitive) ?: JsonNull

    private val WHITESPACE = Regex("\\s+")
}

/**
 * The self-approval rule (APPROBATION, 28/10; JOURNAL 30/10): nobody says yes to their own action while another
 * approver exists. A no from them always passes (it withdraws the request).
 */
object SelfApproval {
    /**
     * What the rule says of an answer, without writing anything.
     *
     * @param askedBy Who asked for the action.
     * @param approvers The gate's approvers.
     * @param answerer Who answers.
     * @param answer Yes or no.
     * @param policy The organization's setting.
     * @return [Verdict.Allowed] (with the self-approved mark) or [Verdict.Refused] with the other approvers.
     */
    fun check(
        askedBy: List<String>,
        approvers: List<String>,
        answerer: String,
        answer: Answer,
        policy: SelfApprovalPolicy,
    ): Verdict {
        val self = askedBy.any { same(it, answerer) }
        if (!self || answer == Answer.NO) return Verdict.Allowed(selfApproved = false)
        val others = othersThan(askedBy, approvers)
        // Self-approval passes only for the one declared approver: two approvers who both asked (the developer and
        // the pull request author) never approve their shared request.
        val sole = approvers.all { same(it, answerer) }
        return if (policy == SelfApprovalPolicy.SOLE_APPROVER && sole) {
            Verdict.Allowed(selfApproved = true)
        } else {
            Verdict.Refused(others)
        }
    }

    /**
     * Who can still say yes among [pendingApprovers]: those who did not ask; if nobody else is left and the
     * organization allows it, the asker who is the only approver; otherwise nobody (add an approver).
     *
     * @param askedBy Who asked.
     * @param pendingApprovers The approvers whose request is still pending.
     * @param policy The organization's setting.
     * @return The approvers to wait for.
     */
    fun waitingFor(askedBy: List<String>, pendingApprovers: List<String>, policy: SelfApprovalPolicy): List<String> {
        val others = othersThan(askedBy, pendingApprovers)
        return when {
            others.isNotEmpty() -> others
            policy == SelfApprovalPolicy.SOLE_APPROVER && pendingApprovers.size == 1 -> pendingApprovers
            else -> emptyList()
        }
    }

    /**
     * Adds people to a list of askers, without duplicates (case ignored, as GitHub logins).
     *
     * @param current The askers so far.
     * @param more The people to add; blank ones are ignored.
     * @return The askers.
     */
    fun withAskers(current: List<String>, vararg more: String): List<String> {
        val out = current.toMutableList()
        for (person in more) if (person.isNotBlank() && out.none { same(it, person) }) out += person
        return out
    }

    private fun othersThan(askedBy: List<String>, approvers: List<String>) =
        approvers.filter { approver -> askedBy.none { same(it, approver) } }

    /**
     * Compares two identities, case ignored.
     *
     * @param a One identity.
     * @param b Another.
     * @return True when they name the same person.
     */
    fun same(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    /** What the rule says. */
    sealed interface Verdict {
        /**
         * The answer may be given.
         *
         * @property selfApproved True when the yes comes from the only approver, who also asked.
         */
        data class Allowed(val selfApproved: Boolean) : Verdict

        /**
         * The answer is refused.
         *
         * @property others Who must say yes instead.
         */
        data class Refused(val others: List<String>) : Verdict
    }
}

/** What the server reads in a command itself, never trusting the hook's reading of it (audit A6, CORRECTIFS-3). */
object GateCommands {
    // Another repository (GIT_DIR, --git-dir, --work-tree), another config (GIT_CONFIG_*, --config-env), another
    // remote or URL (`-c remote.*`, `-c url.*`…): what git sends is no longer what the hook read. The prototype's
    // expression.
    private val redirect = Regex(
        "\\bGIT_(DIR|WORK_TREE|CONFIG\\w*|NAMESPACE|OBJECT_DIRECTORY|ALTERNATE_OBJECT_DIRECTORIES|COMMON_DIR" +
            "|INDEX_FILE)=" +

            "|--git-dir\\b|--work-tree\\b|--namespace\\b|--config-env\\b" +
            "|(^|\\s)-c\\s*[\"']?(remote|url|branch|push|core|include|includeIf)\\.",
        RegexOption.IGNORE_CASE,
    )
    private val push = Regex("\\bgit\\b[^\\n;&|]*\\bpush\\b")

    /**
     * Tells whether a command changes where git reads or sends.
     *
     * @param command The command.
     * @return True when the yes could not be tied to what git really sends.
     */
    fun redirected(command: String): Boolean = redirect.containsMatchIn(command)

    /**
     * Tells whether a command runs `git … push`.
     *
     * @param command The command.
     * @return True for a push.
     */
    fun mentionsPush(command: String): Boolean = push.containsMatchIn(command)
}
