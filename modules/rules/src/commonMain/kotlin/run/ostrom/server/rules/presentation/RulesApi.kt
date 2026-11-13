// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// The routes and bodies of `ostrom-core/api/openapi.yaml` (tags `rules`, `approvals` for the decisions, `gates` for the
// policy), field for field, same JSON, until ostrom-core publishes their models: then this file keeps the resources.

/**
 * `/api/v1/rules`: the rules of the caller's organization (`listRules`, `createRule`).
 *
 * @property repo Only the rules that cover this repository.
 * @property status Only this status.
 * @property group Only this group.
 * @property after The `next` of the previous page.
 * @property limit The page size, 1 to 1 000 (1 000 by default).
 */
@Serializable
@Resource("/api/v1/rules")
class RulesApi(
    val repo: String? = null,
    val status: String? = null,
    val group: String? = null,
    val after: String? = null,
    val limit: Int? = null,
) {
    /**
     * `/{id}`: one rule (`getRule`, `updateRule`, `deleteRule`).
     *
     * @property parent The rules.
     * @property id The rule id.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: RulesApi = RulesApi(), val id: String)

    /**
     * `/bulk`: several decisions at once (`bulkUpdateRules`).
     *
     * @property parent The rules.
     */
    @Serializable
    @Resource("bulk")
    class Bulk(val parent: RulesApi = RulesApi())
}

/**
 * `/api/v1/decisions`: the organization's signed decisions (`listDecisions`).
 *
 * @property subjectKind Only this kind (`rule`, `policy`).
 * @property subjectId Only this subject.
 * @property before The cursor: only the decisions older than this one.
 */
@Serializable
@Resource("/api/v1/decisions")
class DecisionsApi(val subjectKind: String? = null, val subjectId: String? = null, val before: String? = null)

/** `/api/v1/gates/policy`: the self-approval policy (`getGatePolicy`, `setGatePolicy`). */
@Serializable
@Resource("/api/v1/gates/policy")
class GatePolicyApi

/** Scopes of a token on these routes (a browser session holds every user scope, and only it decides). */
object RuleScopes {
    /** Reads the rules and the decisions. */
    const val READ: String = "rules:read"
}

/**
 * A rule (contract `Rule`).
 *
 * @property id The rule id.
 * @property statement What it says.
 * @property output What it produces.
 * @property group The display group.
 * @property scope The repositories or paths it covers.
 * @property sources Where it was found.
 * @property why Why it holds.
 * @property strength `required` or `recommended`.
 * @property confidence From 0 to 1.
 * @property status `proposed`, `accepted`, `edited` or `rejected`.
 * @property foundBy Who found or wrote it.
 * @property foundAt When.
 * @property reviewBy Its review date.
 * @property decidedBy Who decided it last.
 * @property decidedAt When.
 * @property origin `learned` or `written`.
 * @property gate The gate it puts on boundary actions.
 */
@Serializable
data class RuleDto(
    val id: String,
    val statement: String,
    val output: String,
    val group: String,
    val scope: List<String>,
    val sources: List<RuleSourceDto>,
    val why: String? = null,
    val strength: String,
    val confidence: Double,
    val status: String,
    val foundBy: String,
    val foundAt: String,
    val reviewBy: String? = null,
    val decidedBy: String? = null,
    val decidedAt: String? = null,
    val origin: String? = null,
    val gate: RuleGateDto? = null,
)

/**
 * Where a rule was found (contract `RuleSource`).
 *
 * @property kind What kind of source.
 * @property ref The file, pull request or commit.
 * @property url A link.
 */
@Serializable
data class RuleSourceDto(val kind: String, val ref: String, val url: String? = null)

/**
 * A rule's gate (contract `RuleGate`).
 *
 * @property actions The actions it covers.
 * @property approvers Who may say yes.
 * @property reason Why them.
 * @property checks The checks its actions need; empty, a declared run will do.
 */
@Serializable
data class RuleGateDto(
    val actions: List<GateActionDto>,
    val approvers: List<String> = emptyList(),
    val reason: String? = null,
    val checks: List<RuleGateCheckDto> = emptyList(),
)

/**
 * A check a gate needs (contract `RuleGateCheck`).
 *
 * @property check The id of the check node.
 * @property attested Whether only an attested run (Ostrom runner, CI) satisfies it.
 */
@Serializable
data class RuleGateCheckDto(val check: String, val attested: Boolean = false)

/**
 * An action a gate covers (contract `GateAction`).
 *
 * @property cls The action class.
 * @property label The action, as shown; the class when empty.
 * @property patterns The command patterns.
 * @property repos The repositories; empty for all.
 * @property tools The tools of a custom action.
 */
@Serializable
data class GateActionDto(
    @SerialName("class") val cls: String,
    val label: String = "",
    val patterns: List<String> = emptyList(),
    val repos: List<String> = emptyList(),
    val tools: List<String>? = null,
)

/**
 * A page of rules.
 *
 * @property items The rules, by group then id.
 * @property next The `after` of the next page; absent on the last one.
 */
@Serializable
data class RulesPage(val items: List<RuleDto>, val next: String? = null)

/**
 * A rule written by the team (contract `NewRule`).
 *
 * @property statement What it says.
 * @property group The display group.
 * @property output What it produces.
 * @property scope What it covers.
 * @property strength `required` (default) or `recommended`.
 * @property why Why it holds.
 * @property status `accepted` (default) or `proposed`.
 * @property reviewBy Its review date.
 * @property gate Its gate.
 */
@Serializable
data class NewRuleRequest(
    val statement: String,
    val group: String,
    val output: String? = null,
    val scope: List<String> = emptyList(),
    val strength: String? = null,
    val why: String? = null,
    val status: String? = null,
    val reviewBy: String? = null,
    val gate: RuleGateDto? = null,
)

/**
 * Several decisions at once.
 *
 * @property ids The rules.
 * @property status Their new status.
 */
@Serializable
data class BulkRulesRequest(val ids: List<String>, val status: String)

/**
 * What a bulk decision did.
 *
 * @property updated How many of the rules exist and have the status now.
 */
@Serializable
data class BulkRulesResponse(val updated: Int)

/**
 * A signed decision (contract `Decision`).
 *
 * @property id The decision id.
 * @property at When.
 * @property by Who.
 * @property subject What.
 * @property action What was done.
 * @property before What it was.
 * @property after What it is.
 * @property receiptId Its signed receipt.
 */
@Serializable
data class DecisionDto(
    val id: String,
    val at: String,
    val by: String,
    val subject: DecisionSubjectDto,
    val action: String,
    val before: JsonObject? = null,
    val after: JsonObject? = null,
    val receiptId: String,
)

/**
 * What a decision is about (contract `DecisionSubject`).
 *
 * @property kind `rule` or `policy`.
 * @property id Its id.
 * @property label What people read.
 */
@Serializable
data class DecisionSubjectDto(val kind: String, val id: String, val label: String? = null)

/**
 * A page of decisions.
 *
 * @property items The decisions, newest first.
 * @property next The cursor of the next page (`before`), absent on the last one.
 */
@Serializable
data class DecisionsPage(val items: List<DecisionDto>, val next: String? = null)

/**
 * The self-approval policy.
 *
 * @property selfApproval `sole-approver` or `never`.
 */
@Serializable
data class GatePolicyDto(val selfApproval: String)
