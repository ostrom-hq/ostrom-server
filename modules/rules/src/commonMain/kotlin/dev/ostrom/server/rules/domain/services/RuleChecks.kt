// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.services

import dev.ostrom.server.rules.domain.models.InvalidRuleException
import dev.ostrom.server.rules.domain.models.RuleGate
import dev.ostrom.server.rules.domain.models.RuleGateAction
import kotlinx.datetime.LocalDate

/**
 * The bounds of what the console writes into a rule, checked before anything is stored: text is trimmed, a gate's
 * patterns compile, its approvers are GitHub logins, each once.
 */
object RuleChecks {
    private const val MAX_STATEMENT = 2_000
    private const val MAX_GROUP = 80
    private const val MAX_WHY = 2_000
    private const val MAX_SCOPE = 50
    private const val MAX_SCOPE_ENTRY = 300
    private const val MAX_ACTIONS = 20
    private const val MAX_LABEL = 200
    private const val MAX_PATTERNS = 20
    private const val MAX_PATTERN = 500
    private const val MAX_REPOS = 50
    private const val MAX_REPO = 200
    private const val MAX_TOOLS = 10
    private const val MAX_APPROVERS = 20
    private const val MAX_REASON = 500

    private val output = Regex("[a-z][a-z0-9-]{0,39}")
    private val actionClass = Regex("[a-z][a-z0-9-]{0,79}")
    private val tool = Regex("[A-Za-z][A-Za-z0-9_:.-]{0,39}")
    private val login = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
    private val date = Regex("\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])")

    /**
     * A statement.
     *
     * @param value The text.
     * @return It, trimmed.
     * @throws InvalidRuleException When blank or too long.
     */
    fun statement(value: String): String = text(value, MAX_STATEMENT, "statement")

    /**
     * A group.
     *
     * @param value The text.
     * @return It, trimmed.
     * @throws InvalidRuleException When blank or too long.
     */
    fun group(value: String): String = text(value, MAX_GROUP, "group")

    /**
     * A reason.
     *
     * @param value The text.
     * @return It, trimmed, or null when blank.
     * @throws InvalidRuleException When too long.
     */
    fun why(value: String): String? = value.trim().takeIf { it.isNotEmpty() }?.also {
        ensure(it.length <= MAX_WHY) { "A reason has at most $MAX_WHY characters." }
    }

    /**
     * An output.
     *
     * @param value The text.
     * @return It.
     * @throws InvalidRuleException When not a lower-case word of 1 to 40 characters.
     */
    fun output(value: String): String = value.also {
        ensure(output.matches(it)) { "An output is a lower-case word such as rule, gate or check." }
    }

    /**
     * A scope.
     *
     * @param values The repositories or paths.
     * @return Them, trimmed, each once.
     * @throws InvalidRuleException When too many, or one is blank or too long.
     */
    fun scope(values: List<String>): List<String> {
        ensure(values.size <= MAX_SCOPE) { "A scope names at most $MAX_SCOPE repositories or paths." }
        return values.map { text(it, MAX_SCOPE_ENTRY, "scope entry") }.distinct()
    }

    /**
     * A review date.
     *
     * @param value The date.
     * @return It.
     * @throws InvalidRuleException When not `YYYY-MM-DD`, or not a day of the calendar (`2026-02-31`).
     */
    fun reviewBy(value: String): String = value.also {
        ensure(date.matches(it) && LocalDate.Formats.ISO.parseOrNull(it) != null) {
            "A review date is a day of the calendar, written YYYY-MM-DD."
        }
    }

    /**
     * A gate.
     *
     * @param gate The gate.
     * @return It, its texts trimmed and its approvers each once.
     * @throws InvalidRuleException When an action or an approver is out of bounds.
     */
    fun gate(gate: RuleGate): RuleGate {
        ensure(gate.actions.isNotEmpty() && gate.actions.size <= MAX_ACTIONS) {
            "A gate covers 1 to $MAX_ACTIONS actions."
        }
        ensure(gate.approvers.size <= MAX_APPROVERS) { "A gate has at most $MAX_APPROVERS approvers." }
        val approvers = gate.approvers.map { it.trim() }
        ensure(approvers.all(login::matches)) { "An approver is a GitHub login." }
        val reason = gate.reason?.trim().orEmpty().ifEmpty { null }
        ensure(reason == null || reason.length <= MAX_REASON) { "A reason has at most $MAX_REASON characters." }
        return RuleGate(
            actions = gate.actions.map(::action),
            approvers = approvers.distinctBy { it.lowercase() },
            reason = reason,
        )
    }

    private fun action(action: RuleGateAction): RuleGateAction {
        ensure(actionClass.matches(action.cls)) { "An action class is a lower-case word such as push-main." }
        val label = action.label.trim().ifEmpty { action.cls }
        ensure(label.length <= MAX_LABEL) { "An action label has at most $MAX_LABEL characters." }
        ensure(action.patterns.size <= MAX_PATTERNS) { "An action has at most $MAX_PATTERNS patterns." }
        val patterns = action.patterns.map { it.trim() }.filter { it.isNotEmpty() }
        ensure(patterns.all { it.length <= MAX_PATTERN && compiles(it) }) {
            "Each pattern of ${action.cls} is a regular expression of at most $MAX_PATTERN characters."
        }
        ensure(action.repos.size <= MAX_REPOS) { "An action names at most $MAX_REPOS repositories." }
        val repos = action.repos.map { text(it, MAX_REPO, "repository") }.distinct()
        val tools = action.tools?.let { list ->
            ensure(list.size <= MAX_TOOLS && list.all(tool::matches)) { "An action watches at most $MAX_TOOLS tools." }
            list.distinct()
        }
        return RuleGateAction(action.cls, label, patterns, repos, tools)
    }

    private fun text(value: String, max: Int, name: String): String = value.trim().also {
        ensure(it.isNotEmpty() && it.length <= max) { "A $name has 1 to $max characters." }
    }
}

private fun compiles(pattern: String): Boolean = try {
    Regex(pattern)
    true
} catch (_: IllegalArgumentException) {
    false
}

// Not inline: Kover counts an inline function's own lines, never run as such.
private fun ensure(condition: Boolean, reason: () -> String) {
    if (!condition) throw InvalidRuleException(reason())
}
