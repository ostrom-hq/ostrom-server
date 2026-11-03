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
 *
 * An item stays small however many rules cover the action (a replay holds up to 1 000 of them, stored and served
 * whole): it lists at most [MAX_LISTED] rules and approvers, with their counts, each statement cut to
 * [MAX_STATEMENT] characters, and its `why` names at most [MAX_NAMED] of each. The rule's id leads to the whole rule.
 */
object GateReplay {
    /** The outcomes, as the items say them. */
    const val PASSED: String = "passed"

    /** The action would have waited for a yes. */
    const val ASKED: String = "asked"

    /** Nobody could have said yes. */
    const val BLOCKED: String = "blocked"

    /** The rules, and the approvers, an item lists at most. */
    const val MAX_LISTED: Int = 10

    /** The characters of a statement an item keeps; a longer one ends with `…`. */
    const val MAX_STATEMENT: Int = 280

    /** The rules, and the approvers, a `why` names at most. */
    const val MAX_NAMED: Int = 3

    private const val NAMED_STATEMENT = 120

    /**
     * Prepares rules for replay: their patterns compiled once, a pattern that does not compile left out.
     *
     * @param rules The rules; one without a gate covers nothing.
     * @return The replayable rules.
     */
    fun prepare(rules: List<Rule>): List<ReplayedRule> = rules.mapNotNull { rule ->
        rule.gate?.let { gate ->
            val approvers = gate.approvers.map { it to it.lowercase() }
            val actions = gate.actions.map { ReplayedAction(it, compiled(it), it.repos.map(String::lowercase)) }
            ReplayedRule(rule, gate, actions, approvers, rule.scope.map(String::lowercase))
        }
    }

    /**
     * Replays the rules on one action.
     *
     * @param gate The action, as its gate recorded it.
     * @param rules The prepared rules.
     * @param policy The organization's self-approval policy.
     * @return The item: what happened, what the rules would have done, and why.
     */
    fun replay(gate: Gate, rules: List<ReplayedRule>, policy: SelfApprovalPolicy): JsonObject =
        replayAll(listOf(gate), rules, policy).single()

    /**
     * Replays the rules on actions, in their order. The rules that cover an action depend only on its repository,
     * class and command: they are found once for each such subject, not once per action (a replay holds up to 1 000
     * actions, most of them alike).
     *
     * @param gates The actions, as their gates recorded them.
     * @param rules The prepared rules.
     * @param policy The organization's self-approval policy.
     * @return One item per action.
     */
    fun replayAll(gates: List<Gate>, rules: List<ReplayedRule>, policy: SelfApprovalPolicy): List<JsonObject> {
        val coverages = HashMap<Triple<String?, String, String>, Coverage>()
        return gates.map { gate ->
            val subject = gate.subject
            val coverage = coverages.getOrPut(Triple(subject.repo, subject.actionClass, subject.command)) {
                Coverage(Target(subject), rules)
            }
            item(gate, coverage, policy)
        }
    }

    private fun item(gate: Gate, coverage: Coverage, policy: SelfApprovalPolicy): JsonObject {
        val subject = gate.subject
        val covering = coverage.rules
        val approvers = coverage.approvers.values.toList()
        val askers = gate.askedBy.map { it.lowercase() }.toSet()
        val others = coverage.approvers.filterKeys { it !in askers }.values.toList()
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
            put("ruleCount", covering.size)
            putJsonArray("rules") {
                covering.take(MAX_LISTED).forEach { addJsonObject { rule(it) } }
            }
            put("approverCount", may.size)
            putJsonArray("approvers") { may.take(MAX_LISTED).forEach { add(it) } }
            put("why", why(outcome, covering, may, approvers))
            put("simulation", true)
        }
    }

    private fun JsonObjectBuilder.rule(replayed: ReplayedRule) {
        put("id", replayed.rule.id)
        put("statement", cut(replayed.rule.statement, MAX_STATEMENT))
        put("status", replayed.rule.status.value)
        putJsonArray("approvers") { replayed.gate.approvers.forEach { add(it) } }
    }

    private fun why(outcome: String, covering: List<ReplayedRule>, may: List<String>, approvers: List<String>): String {
        val named = names(covering, "; ") { "${it.rule.id}: ${cut(it.rule.statement, NAMED_STATEMENT)}" }
        return when (outcome) {
            PASSED -> "No replayed rule gates this action: it would have run without asking anything."
            ASKED -> "It would have waited for a yes from ${names(may, ", ") { it }} ($named)."
            else -> if (approvers.isEmpty()) {
                "It would have been blocked: the gate names no approver ($named)."
            } else {
                "It would have been blocked: only ${names(approvers, ", ") { it }}, who asked, may approve it, and " +
                    "the organization refuses a yes on one's own action ($named)."
            }
        }
    }

    // The first MAX_NAMED, then how many more: only those named are written out.
    private fun <T> names(all: List<T>, separator: String, name: (T) -> String): String {
        val more = all.size - MAX_NAMED
        val shown = all.take(MAX_NAMED).joinToString(separator, transform = name)
        return if (more > 0) "$shown$separator$more more" else shown
    }

    private fun cut(text: String, max: Int): String = if (text.length > max) text.take(max - 1) + "…" else text

    // What the action reached: a push's refs, a merge's pull request.
    private fun target(subject: GateSubject): String? {
        val push = subject.push?.let { push -> push.refs.joinToString(", ") { it.ref } }
        val pull = subject.pullRequest?.let { "pull request #${it.number} into ${it.base}" }
        return pull ?: push
    }

    // The rules covering one subject, and their approvers by lowered name, the first spelling kept.
    private class Coverage(target: Target, all: List<ReplayedRule>) {
        val rules = all.filter { it.covers(target) }
        val approvers = LinkedHashMap<String, String>().also { byKey ->
            rules.forEach { rule -> rule.approverKeys.forEach { (name, key) -> byKey.getOrPut(key) { name } } }
        }
    }

    // A subject's names, lowered once: `owner/name`, the short name and, as the catalogue names a repository, the
    // aliases of both.
    internal class Target(subject: GateSubject) {
        val full = subject.repo.orEmpty().lowercase()
        val short = full.substringAfterLast('/')
        val shortDir = "$short/"
        val names: Set<String> = listOfNotNull(subject.repo, subject.repo?.substringAfterLast('/'))
            .flatMap { GatesCatalog.aliases(it) + it.lowercase() }.toSet()
        val actionClass = subject.actionClass
        val command = subject.command
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
        // Each approver with its lowered name, the key two spellings of one person share.
        internal val approverKeys: List<Pair<String, String>>,
        private val scope: List<String>,
    ) {
        // Scoped to repositories or paths, it covers an action in one of them; unscoped, every action.
        internal fun covers(target: Target): Boolean {
            val inScope = scope.isEmpty() || (target.full.isNotEmpty() && scope.any {
                it == target.full || it == target.short || it.startsWith(target.shortDir)
            })
            return inScope && actions.any { it.covers(target) }
        }
    }

    /**
     * A gate's action, its patterns compiled.
     *
     * @property action The action.
     */
    class ReplayedAction internal constructor(
        val action: RuleGateAction,
        private val patterns: List<Regex>,
        private val repos: List<String>,
    ) {
        // The catalogue names a repository by its short name or an alias of it; the history by `owner/name`.
        internal fun covers(target: Target): Boolean {
            val inRepo = repos.isEmpty() || repos.any { it in target.names }
            val same = action.cls == target.actionClass || patterns.any { it.containsMatchIn(target.command) }
            return inRepo && same
        }
    }
}
