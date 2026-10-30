// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.core.api.models.GateAction
import dev.ostrom.core.api.models.NodeRun
import dev.ostrom.core.api.models.NodeRunOn
import dev.ostrom.core.api.models.NodeSuggestions
import dev.ostrom.core.api.models.StringListSuggestion
import dev.ostrom.core.api.models.StringSuggestion
import dev.ostrom.core.api.models.Workflow
import dev.ostrom.core.api.models.WorkflowEdge
import dev.ostrom.core.api.models.WorkflowNode
import dev.ostrom.core.api.models.WorkflowNodeKind
import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.server.compiler.domain.models.CompileInputs
import dev.ostrom.server.compiler.domain.models.DecisionDoc
import dev.ostrom.server.compiler.domain.models.RuleDoc
import dev.ostrom.server.compiler.domain.models.WorkflowDoc
import dev.ostrom.server.compiler.domain.services.CompilerDocs
import dev.ostrom.server.compiler.domain.services.HarnessStamp
import dev.ostrom.server.compiler.domain.services.PackageCompiler
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

internal const val AT = "2026-10-30T08:00:00.000Z"
internal val NOW: Instant = Instant.parse("2026-10-30T08:45:00Z")

internal fun node(
    id: String,
    kind: WorkflowNodeKind,
    label: String = id,
    detail: String? = null,
    run: NodeRun? = null,
    suggested: NodeSuggestions? = null,
    actions: List<GateAction>? = null,
    approvers: List<String>? = null,
    ruleIds: List<String>? = null,
    owner: String? = null,
): WorkflowNode = WorkflowNode(
    id = id,
    kind = kind,
    label = label,
    detail = detail,
    owner = owner,
    approvers = approvers,
    ruleIds = ruleIds,
    actions = actions,
    run = run,
    suggested = suggested,
)

internal fun workflow(
    id: String,
    nodes: List<WorkflowNode>,
    edges: List<Pair<String, String?>> = emptyList(),
    status: WorkflowStatus = WorkflowStatus.ACCEPTED,
    name: String = id,
    domain: String = "",
): Workflow = Workflow(
    id = id,
    name = name,
    domain = domain,
    nodes = nodes,
    edges = edges.map { (from, to) -> WorkflowEdge(from, to) },
    status = status,
    foundAt = AT,
)

/** A chain: each node leads to the next. */
internal fun chain(vararg ids: String): List<Pair<String, String?>> = ids.toList().zipWithNext()

internal fun on(vararg classes: String, patterns: List<String>? = null) =
    NodeRun(on = NodeRunOn(classes.toList(), patterns))

internal fun text(value: String) = StringSuggestion(value, listOf("evidence"), at = AT)

internal fun texts(vararg value: String) = StringListSuggestion(value.toList(), listOf("evidence"), at = AT)

internal fun action(cls: String, repos: List<String> = emptyList(), patterns: List<String> = emptyList()) =
    GateAction(cls, cls, patterns, repos)

internal fun doc(workflow: Workflow): WorkflowDoc =
    CompilerDocs.workflow(PackageCompiler.json.encodeToJsonElement(Workflow.serializer(), workflow).jsonObject)

internal fun rule(
    id: String,
    scope: List<String> = emptyList(),
    status: String = "accepted",
    strength: String = "required",
    confidence: Double = 0.5,
    group: String? = null,
    sources: List<String> = emptyList(),
): RuleDoc = RuleDoc(JsonObject(emptyMap()), id, "Rule $id.", scope, sources, status, strength, confidence, group)

internal fun decision(
    subjectId: String,
    by: String = "dev-a",
    at: String = AT,
    kind: String = "workflow",
    receipt: String? = "rcpt_1",
) =
    DecisionDoc("dec_$subjectId$at", kind, subjectId, by, at, receipt)

internal fun inputs(
    workflows: List<Workflow>,
    repo: String? = "acme-api",
    rules: List<RuleDoc> = emptyList(),
    decisions: List<DecisionDoc> = workflows.map { decision(it.id) },
    repos: List<String> = listOf("acme-api"),
    prodApprover: String? = null,
    codePublishers: List<String>? = null,
): CompileInputs = CompileInputs(
    repo = repo,
    serverUrl = "https://acme.example",
    workflows = workflows.map(::doc),
    rules = rules,
    decisions = decisions,
    repos = repos,
    prodApprover = prodApprover,
    codePublishers = codePublishers,
    now = NOW,
)

internal suspend fun compiled(inputs: CompileInputs) = PackageCompiler.compile(inputs) { digest ->
    HarnessStamp("h202610300845-${digest.take(8)}", null)
}
