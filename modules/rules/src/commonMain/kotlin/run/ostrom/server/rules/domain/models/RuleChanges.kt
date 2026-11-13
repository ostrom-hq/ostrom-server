// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.domain.models

/**
 * A rule written by the team (contract `NewRule`).
 *
 * @property statement What it says.
 * @property group The display group.
 * @property output What it produces; `gate` when [gate] is set and none is given, else `rule`.
 * @property scope The repositories or paths it covers.
 * @property strength Must or should; must by default.
 * @property why Why it holds.
 * @property status `accepted` (the default: the person who writes it decides it) or `proposed`.
 * @property reviewBy The review date of a decided rule; 90 days after the decision by default.
 * @property gate The gate it puts on boundary actions.
 */
data class NewRule(
    val statement: String,
    val group: String,
    val output: String? = null,
    val scope: List<String> = emptyList(),
    val strength: RuleStrength = RuleStrength.REQUIRED,
    val why: String? = null,
    val status: RuleStatus = RuleStatus.ACCEPTED,
    val reviewBy: String? = null,
    val gate: RuleGate? = null,
)

/**
 * A decision or an edit on a rule (contract `updateRule`): each field is changed only when set.
 *
 * @property statement A new statement; without [status], the rule becomes `edited`.
 * @property status A new status.
 * @property strength A new strength.
 * @property reviewBy A new review date.
 * @property scope A new scope.
 * @property group A new group.
 * @property why A new reason.
 * @property output A new output.
 * @property gate The rule's gate: kept, removed or replaced.
 */
data class RulePatch(
    val statement: String? = null,
    val status: RuleStatus? = null,
    val strength: RuleStrength? = null,
    val reviewBy: String? = null,
    val scope: List<String>? = null,
    val group: String? = null,
    val why: String? = null,
    val output: String? = null,
    val gate: GateChange = GateChange.Keep,
)

/** What an edit does to a rule's gate. */
sealed interface GateChange {
    /** The gate stays as it is. */
    data object Keep : GateChange

    /** The rule no longer gates anything. */
    data object Remove : GateChange

    /**
     * The rule's gate becomes [gate].
     *
     * @property gate The new gate.
     */
    data class Replace(val gate: RuleGate) : GateChange
}
