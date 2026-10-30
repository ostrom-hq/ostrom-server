// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.domain.models

import dev.ostrom.core.api.models.Workflow
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * A workflow as the organization saved it: the raw JSON (digests are taken on it, field for field, as the prototype
 * did) and its model.
 *
 * @property raw The JSON the console saved.
 * @property workflow The same, read into the model.
 */
data class WorkflowDoc(val raw: JsonObject, val workflow: Workflow)

/**
 * A team rule, read by the rules harness. Its digest is taken on [raw], as stored.
 *
 * @property raw The rule as stored.
 * @property id The rule id.
 * @property statement What it says.
 * @property scope What it covers (paths, repositories, topics).
 * @property sourceRefs Where it was found.
 * @property status `proposed`, `accepted`, `edited` or `rejected`.
 * @property strength `required` or another strength.
 * @property confidence How sure the analysis is.
 * @property group The domain it belongs to (one skill per group).
 */
data class RuleDoc(
    val raw: JsonObject,
    val id: String,
    val statement: String,
    val scope: List<String>,
    val sourceRefs: List<String>,
    val status: String,
    val strength: String,
    val confidence: Double,
    val group: String?,
) {
    /** True once the team accepted or edited it. */
    val decided: Boolean get() = status == "accepted" || status == "edited"
}

/**
 * A signed decision on a workflow or a rule (who decided, when, its receipt).
 *
 * @property id The decision id.
 * @property subjectKind `workflow` or `rule`.
 * @property subjectId The workflow or rule.
 * @property by Who decided.
 * @property at When, ISO-8601 with milliseconds.
 * @property receiptId Its signed receipt.
 */
data class DecisionDoc(
    val id: String,
    val subjectKind: String,
    val subjectId: String,
    val by: String,
    val at: String,
    val receiptId: String?,
)

/**
 * Everything a compilation reads: the organization's workflows, rules and decisions, its repositories and settings.
 *
 * @property repo The repository the session runs in, or null for the organization.
 * @property serverUrl The server's public URL.
 * @property workflows The workflows.
 * @property rules The rules.
 * @property decisions The decisions.
 * @property repos The organization's repository names.
 * @property prodApprover The default approver of a gate that names none.
 * @property codePublishers Who may publish a node that runs code; null for every person who decides.
 * @property now The compilation time.
 */
data class CompileInputs(
    val repo: String?,
    val serverUrl: String,
    val workflows: List<WorkflowDoc>,
    val rules: List<RuleDoc>,
    val decisions: List<DecisionDoc>,
    val repos: List<String>,
    val prodApprover: String?,
    val codePublishers: List<String>?,
    val now: Instant,
)
