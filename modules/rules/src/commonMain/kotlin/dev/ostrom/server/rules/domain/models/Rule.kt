// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.models

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A rule of an organization (contract `Rule`), learned by the analysis or written by the team. A decided rule (accepted
 * or edited) belongs to the team: no analysis changes it, and the compiler hands it to the agents. A rule with a [gate]
 * also puts a human yes on boundary actions.
 *
 * @property orgId The organization that owns it; every read is scoped by it.
 * @property id The rule id.
 * @property statement What it says.
 * @property output What it produces (`rule`, `gate`, `check`, `note`…), as the analysis classes it.
 * @property group The display group (`Reviews`, `Releases`…).
 * @property scope The repositories or paths it covers; empty for the whole organization.
 * @property sources Where it was found.
 * @property why Why it holds.
 * @property strength Must or should.
 * @property confidence How sure the analysis is, from 0 to 1; 1 for a rule the team wrote.
 * @property status Where the team's decision is.
 * @property foundBy The analysis agent that found it, or the person who wrote it.
 * @property foundAt When.
 * @property reviewBy The date (`YYYY-MM-DD`) to review a decided rule by.
 * @property decidedBy Who decided it last.
 * @property decidedAt When.
 * @property origin `learned` (the analysis) or `written` (the team).
 * @property gate The gate it puts on boundary actions, if any.
 * @property updatedAt When it last changed.
 */
data class Rule(
    val orgId: Uuid,
    val id: String,
    val statement: String,
    val output: String,
    val group: String,
    val scope: List<String>,
    val sources: List<RuleSource>,
    val why: String?,
    val strength: RuleStrength,
    val confidence: Double,
    val status: RuleStatus,
    val foundBy: String,
    val foundAt: Instant,
    val reviewBy: String?,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val origin: String?,
    val gate: RuleGate?,
    val updatedAt: Instant,
)

/**
 * Where a rule was found.
 *
 * @property kind `code`, `ci`, `pr`, `review`, `doc`, `file`…
 * @property ref The file, pull request or commit.
 * @property url A link to it.
 */
data class RuleSource(val kind: String, val ref: String, val url: String?)

/**
 * The gate a rule puts on boundary actions (contract `RuleGate`): accepted or edited, it blocks them until one of
 * [approvers] says yes; proposed, it only records them; rejected, it gates nothing.
 *
 * @property actions The actions it covers.
 * @property approvers Who may say yes (GitHub logins), in order.
 * @property reason Why these approvers, shown with the gate.
 * @property checks The checks its actions need, each with the attestation it asks for; empty, a declared run will do.
 */
data class RuleGate(
    val actions: List<RuleGateAction>,
    val approvers: List<String>,
    val reason: String?,
    val checks: List<RuleGateCheck> = emptyList(),
)

/**
 * A check a gate needs before its actions pass (contract `RuleGateCheck`).
 *
 * @property check The id of the check node in the organization's workflows.
 * @property attested When true, only a run attested by an Ostrom runner or the CI satisfies the gate, never one the
 * workstation declares.
 */
data class RuleGateCheck(val check: String, val attested: Boolean)

/**
 * One action a gate covers (contract `GateAction`, as workflow gate nodes declare them).
 *
 * @property cls The action class (`push-main`, `merge`, `deploy`… or a custom one).
 * @property label The action, as shown.
 * @property patterns The command patterns that make the action; empty for a built-in class's own.
 * @property repos The repositories it covers; empty for all.
 * @property tools The agent tools it watches; null for the class's default.
 */
data class RuleGateAction(
    val cls: String,
    val label: String,
    val patterns: List<String>,
    val repos: List<String>,
    val tools: List<String>?,
)

/** Must or should (contract `RuleStrength`). */
enum class RuleStrength(val value: String) {
    REQUIRED("required"),
    RECOMMENDED("recommended"),
    ;

    /** Reading the contract's values. */
    companion object {
        /**
         * Reads a value.
         *
         * @param value The contract's value.
         * @return The strength, or null when unknown.
         */
        fun of(value: String): RuleStrength? = entries.firstOrNull { it.value == value }
    }
}

/** Where the team's decision is (contract `RuleStatus`). */
enum class RuleStatus(val value: String) {
    PROPOSED("proposed"),
    ACCEPTED("accepted"),
    EDITED("edited"),
    REJECTED("rejected"),
    ;

    /** Reading the contract's values. */
    companion object {
        /**
         * Reads a value.
         *
         * @param value The contract's value.
         * @return The status, or null when unknown.
         */
        fun of(value: String): RuleStatus? = entries.firstOrNull { it.value == value }
    }
}

/**
 * Which rules to list.
 *
 * @property repo Only the rules that cover this repository (or the whole organization), when set.
 * @property status Only this status, when set.
 * @property group Only this group, when set.
 * @property after Only the rules after this one in the list's order (group, then id), when set.
 */
data class RuleQuery(
    val repo: String? = null,
    val status: RuleStatus? = null,
    val group: String? = null,
    val after: RuleCursor? = null,
)

/**
 * A position in the list of rules: the rules strictly after ([group], [id]) in its order.
 *
 * @property group The group of the last rule of the previous page.
 * @property id Its id.
 */
data class RuleCursor(val group: String, val id: String) {
    /** The opaque form of `next` and `?after=`: the id's length, `~`, the id, then the group, whatever they hold. */
    val wire: String get() = "${id.length}$SEPARATOR$id$group"

    /** Reading a cursor. */
    companion object {
        private const val SEPARATOR = '~'

        /**
         * The cursor after a rule.
         *
         * @param rule The last rule of a page.
         * @return The cursor.
         */
        fun after(rule: Rule): RuleCursor = RuleCursor(rule.group, rule.id)

        /**
         * Reads `?after=`.
         *
         * @param wire The value a previous page gave as `next`.
         * @return The cursor.
         * @throws InvalidRuleException When it is not one.
         */
        fun of(wire: String): RuleCursor {
            val length = wire.substringBefore(SEPARATOR, "").toIntOrNull()
            val rest = wire.substringAfter(SEPARATOR, "")
            if (length == null || length < 1 || rest.length <= length) {
                throw InvalidRuleException("The cursor is not one a previous page gave.")
            }
            return RuleCursor(group = rest.substring(length), id = rest.take(length))
        }
    }
}

/**
 * A page of rules.
 *
 * @property items The rules, by group then id.
 * @property next Where the next page starts, null on the last one.
 */
data class RulePage(val items: List<Rule>, val next: RuleCursor?)
