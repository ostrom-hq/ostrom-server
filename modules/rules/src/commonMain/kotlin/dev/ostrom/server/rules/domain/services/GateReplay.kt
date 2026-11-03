// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.services

import dev.ostrom.server.compiler.domain.services.GatesCatalog
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.models.SelfApprovalPolicy
import dev.ostrom.server.rules.domain.models.Rule
import dev.ostrom.server.rules.domain.models.RuleGate
import dev.ostrom.server.rules.domain.models.RuleGateAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Replays rules on one recorded boundary action, deterministically: no model, the same rules and the same action give
 * the same answer. A rule covers the action when its scope covers the action's repository and one of its gate's
 * actions covers the action: the same class, or a pattern that matches the command, in a repository it names (none:
 * all). Then:
 *
 * - `passed`: no replayed rule covers it; it would have run without asking anything;
 * - `asked`: it would have waited for a yes from one of the approvers who may say it;
 * - `blocked`: nobody may say yes: the gates name no approver, or only the person who asked, whom the organization's
 *   self-approval policy refuses.
 *
 * The tools a gate watches are not replayed: the history holds the actions the hooks already classed.
 */
object GateReplay {
    /** The outcomes, as the items say them. */
    const val PASSED: String = "passed"

    /** The action would have waited for a yes. */
    const val ASKED: String = "asked"

    /** Nobody could have said yes. */
    const val BLOCKED: String = "blocked"

    /**
     * Prepares rules for replay: their patterns compiled once, a pattern that does not compile left out.
     *
     * @param rules The rules; one without a gate covers nothing.
     * @return The replayable rules.
     */
    fun prepare(rules: List<Rule>): List<ReplayedRule> = rules.mapNotNull { rule ->
        rule.gate?.let { gate -> ReplayedRule(rule, gate, gate.actions.map { ReplayedAction(it, compiled(it)) }) }
    }

    /**
     * Replays the rules on one action.
     *
     * @param gate The action, as its gate recorded it.
     * @param rules The prepared rules.
     * @param policy The organization's self-approval policy.
     * @return The item: what happened, what the rules would have done, and why.
     */
    fun replay(gate: Gate, rules: List<ReplayedRule>, policy: SelfApprovalPolicy): JsonObject {
        val subject = gate.subject
        val covering = rules.filter { covers(it.rule.scope, subject.repo) && it.actions.any { a -> a.covers(subject) } }
        val approvers = covering.flatMap { it.gate.approvers }.distinctBy { it.lowercase() }
        val askers = gate.askedBy.map { it.lowercase() }.toSet()
        val others = approvers.filter { it.lowercase() !in askers }
        val alone = policy == SelfApprovalPolicy.SOLE_APPROVER && approvers.size == 1
        val may = if (alone) approvers else others
        val outcome = when {
            covering.isEmpty() -> PASSED
            may.isEmpty() -> BLOCKED
            else -> ASKED
        }
        return buildJsonObject {
            put("gateId", gate.id.toString())
            put("at", gate.createdAt.toString())
            subject.repo?.let { put("repo", it) }
            put("actionClass", subject.actionClass)
            put("action", gate.label)
            target(subject)?.let { put("target", it) }
            putJsonObject("happened") {
                put("mode", gate.mode.name.lowercase())
                put("status", gate.status.name.lowercase())
            }
            put("outcome", outcome)
            putJsonArray("sessions") { gate.sessionIds.forEach { add(it) } }
            putJsonArray("rules") {
                covering.forEach { addJsonObject { rule(it) } }
            }
            putJsonArray("approvers") { may.forEach { add(it) } }
            put("why", why(outcome, covering, may, approvers))
            put("simulation", true)
        }
    }

    private fun JsonObjectBuilder.rule(replayed: ReplayedRule) {
        put("id", replayed.rule.id)
        put("statement", replayed.rule.statement)
        put("status", replayed.rule.status.value)
        putJsonArray("approvers") { replayed.gate.approvers.forEach { add(it) } }
    }

    private fun why(outcome: String, covering: List<ReplayedRule>, may: List<String>, approvers: List<String>): String {
        val named = covering.joinToString("; ") { "${it.rule.id}: ${it.rule.statement}" }
        return when (outcome) {
            PASSED -> "No replayed rule gates this action: it would have run without asking anything."
            ASKED -> "It would have waited for a yes from ${may.joinToString(", ")} ($named)."
            else -> if (approvers.isEmpty()) {
                "It would have been blocked: the gate names no approver ($named)."
            } else {
                "It would have been blocked: only ${approvers.joinToString(", ")}, who asked, may approve it, and " +
                    "the organization refuses a yes on one's own action ($named)."
            }
        }
    }

    // What the action reached: a push's refs, a merge's pull request.
    private fun target(subject: GateSubject): String? {
        val push = subject.push?.let { push -> push.refs.joinToString(", ") { it.ref } }
        val pull = subject.pullRequest?.let { "pull request #${it.number} into ${it.base}" }
        return pull ?: push
    }

    // A rule scoped to repositories or paths covers an action in one of them; unscoped, every action.
    private fun covers(scope: List<String>, repo: String?): Boolean {
        val full = repo.orEmpty().lowercase()
        val short = full.substringAfterLast('/')
        return scope.isEmpty() || (full.isNotEmpty() && scope.any { entry ->
            entry.lowercase().let { it == full || it == short || it.startsWith("$short/") }
        })
    }

    private fun compiled(action: RuleGateAction): List<Regex> = action.patterns.mapNotNull { pattern ->
        try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * A rule ready to replay.
     *
     * @property rule The rule.
     * @property gate Its gate.
     */
    class ReplayedRule internal constructor(
        val rule: Rule,
        val gate: RuleGate,
        internal val actions: List<ReplayedAction>,
    )

    /**
     * A gate's action, its patterns compiled.
     *
     * @property action The action.
     */
    class ReplayedAction internal constructor(val action: RuleGateAction, private val patterns: List<Regex>) {
        internal fun covers(subject: GateSubject): Boolean {
            // The catalogue names a repository by its short name or an alias of it; the history by `owner/name`.
            val names = listOf(subject.repo, subject.repo?.substringAfterLast('/'))
            val inRepo = names.any { GatesCatalog.covers(action.repos, it) }
            val same = action.cls == subject.actionClass || patterns.any { it.containsMatchIn(subject.command) }
            return inRepo && same
        }
    }
}
