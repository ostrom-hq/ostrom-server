// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.domain.services

import run.ostrom.core.api.models.AgentEvent
import run.ostrom.core.api.models.CompiledHook
import run.ostrom.core.api.models.CompiledNode
import run.ostrom.core.api.models.CompiledNotify
import run.ostrom.core.api.models.CompiledScript
import run.ostrom.core.api.models.CompiledTrigger
import run.ostrom.core.api.models.PackageFile
import run.ostrom.core.api.models.PackageFileMode
import run.ostrom.core.api.models.PackageHarness
import run.ostrom.core.api.models.PackageMcp
import run.ostrom.core.api.models.PackageMcpServer
import run.ostrom.core.api.models.PackageMcpTool
import run.ostrom.core.api.models.PackageMode
import run.ostrom.core.api.models.PackageWorkflow
import run.ostrom.core.api.models.RunLocation
import run.ostrom.core.api.models.ScriptApproval
import run.ostrom.core.api.models.ScriptSuggestion
import run.ostrom.core.api.models.StringSuggestion
import run.ostrom.core.api.models.SuggestedChannel
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.core.receipts.Digests
import run.ostrom.server.compiler.domain.models.ActionClasses
import run.ostrom.server.compiler.domain.models.CatalogEntry
import run.ostrom.server.compiler.domain.models.CompileInputs
import run.ostrom.server.compiler.domain.models.WorkflowDoc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The harness version a digest already has, or the one to give it.
 *
 * @property version `h<stamp>-<digest>`.
 * @property receiptId The receipt signed for it.
 */
data class HarnessStamp(val version: String, val receiptId: String?)

/**
 * A compiled session package, before its version and signature (`compileBody` of the prototype).
 *
 * @property repo The repository, or null for the organization.
 * @property harness The rules harness it carries.
 * @property harnessRules The rules the harness carries (for its receipt).
 * @property workflows The workflows it compiles.
 * @property nodes What each node became.
 * @property hooks The hooks.
 * @property scripts The scripts.
 * @property notifications The notifications.
 * @property files The files, by path in `localeCompare` order.
 * @property body The body's JSON, exactly what the prototype hashes and signs.
 */
data class CompiledBody(
    val repo: String?,
    val harness: PackageHarness,
    val harnessRules: List<HarnessRule>,
    val workflows: List<PackageWorkflow>,
    val nodes: List<CompiledNode>,
    val hooks: List<CompiledHook>,
    val scripts: List<CompiledScript>,
    val notifications: List<CompiledNotify>,
    val files: List<PackageFile>,
    val body: JsonObject,
) {
    /** What changes what a session receives (not the time, not the server's address). */
    val contentDigest: String
        get() = Digests.digestOf(
            JsonObject(
                body.filterKeys { it != "server" } + mapOf(
                    "harness" to buildJsonObject { put("digest", harness.digest) },
                    "files" to buildJsonArray {
                        files.forEach { file ->
                            add(buildJsonObject { put("p", file.path); put("m", modeOf(file)); put("s", file.sha256) })
                        }
                    },
                ),
            ),
        )

    private fun modeOf(file: PackageFile): String =
        if (file.mode == PackageFileMode.EXECUTABLE) "executable" else "file"
}

/**
 * Compiles the workflows of a repository into the session package (`server/src/compiler/compile.ts`): a check
 * becomes a PreToolUse hook and a script, a gate a PreToolUse hook (the human yes), a notify a PostToolUse hook and a
 * message sent by the server, an agent node a sub-agent and a Codex role, a step a skill, a trigger a line of the
 * session context; with the rules harness.
 */
object PackageCompiler {
    /** The compiler's version, in every package. */
    const val VERSION: String = "0.1.0"

    /** The package format. */
    const val FORMAT: String = "ostrom.package/1"

    private const val DEFAULT_TIMEOUT = 120
    private const val MIN_TIMEOUT = 5
    private const val MAX_TIMEOUT = 540
    private const val MIN_COMPILE_ALIAS = 4

    /** The JSON of the core models in a package: absent fields stay absent, as in the prototype. */
    val json: Json = Json {
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * The workflows that apply to a repository; all of them for the organization. A workflow that lists its
     * repositories (`repos`, [WorkflowRepos]) applies to them only (R6). One that lists none applies when its gate
     * covers one of the repository's actions, when it names the repository, or when its gate declares it.
     *
     * @param inputs The organization's sources.
     * @return The workflows, in their order.
     */
    fun workflowsFor(inputs: CompileInputs): List<WorkflowDoc> {
        val repo = inputs.repo ?: return inputs.workflows
        val all = inputs.workflows.filter { WorkflowRepos.covers(it.repos, repo) }
        val catalog = GatesCatalog(all.map { it.workflow } + inputs.ruleGates, inputs.prodApprover).catalog(repo)
        val viaGate = catalog.mapNotNull { it.gate?.workflowId }.toSet()
        return all.filter { doc ->
            val workflow = doc.workflow
            doc.repos != null ||
                workflow.id in viaGate ||
                compileAliases(repo).any { GatesCatalog.mentions(text(workflow), it) } ||
                gateActions(workflow).any { action -> action.repos.any { it.equals(repo, ignoreCase = true) } }
        }
    }

    private fun compileAliases(repo: String): List<String> =
        GatesCatalog.aliases(repo).filter { it.length >= MIN_COMPILE_ALIAS }

    private fun text(workflow: Workflow): String =
        (listOf(workflow.name, workflow.domain) + workflow.nodes.map { "${it.label} ${it.detail.orEmpty()}" })
            .joinToString(" ")
            .lowercase()

    private fun gateActions(workflow: Workflow) =
        workflow.nodes.filter { it.kind == WorkflowNodeKind.GATE }.flatMap { it.actions.orEmpty() }

    /**
     * The order of a workflow's nodes: from the nodes nothing leads to (the first node when every node has a parent),
     * breadth first along the edges, then the nodes no edge reaches.
     *
     * @param workflow The workflow.
     * @return Its nodes in order.
     */
    fun orderOf(workflow: Workflow): List<WorkflowNode> {
        val reached = workflow.edges.mapNotNull { it.to }.toSet()
        val queue = ArrayDeque(workflow.nodes.filter { it.id !in reached }.ifEmpty { workflow.nodes.take(1) })
        val seen = mutableSetOf<String>()
        val out = mutableListOf<WorkflowNode>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (seen.add(node.id)) {
                out += node
                workflow.edges.filter { it.from == node.id }
                    .mapNotNull { edge -> workflow.nodes.firstOrNull { it.id == edge.to } }
                    .filter { it.id !in seen }
                    .forEach { queue.addLast(it) }
            }
        }
        return out + workflow.nodes.filter { it.id !in seen }
    }

    /**
     * Compiles the package body of a repository.
     *
     * @param inputs The organization's sources.
     * @param workflows The workflows to compile: by default those that apply to the repository ([workflowsFor]); a
     *   caller that already chose them passes them as they are, as the prototype's `opts.workflows`.
     * @param stamp The version of the harness with this digest (an earlier one, or a new one).
     * @return The body.
     */
    suspend fun compile(
        inputs: CompileInputs,
        workflows: List<WorkflowDoc> = workflowsFor(inputs),
        stamp: suspend (digest: String) -> HarnessStamp,
    ): CompiledBody {
        val repo = inputs.repo
        val docs = workflows.sortedWith(compareBy(JsText.localeOrder) { it.workflow.id })
        val catalog = GatesCatalog.of(inputs.workflows, inputs.ruleGates, inputs.prodApprover).catalog(repo)
        val state = CompileState(inputs, catalog)
        docs.forEach { state.add(it.workflow) }

        val rulesHarness = RulesHarness(inputs.rules, inputs.decisions, inputs.repos)
        val picked = rulesHarness.rulesFor(repo)
        val digest = rulesHarness.digest(repo, picked, catalog)
        val harnessStamp = stamp(digest)
        val harnessContent = rulesHarness.build(repo, inputs.serverUrl, harnessStamp.version, picked, catalog)
        harnessContent.files.forEach { (path, content) -> state.file(path, content, PackageFileMode.FILE) }

        val harness = PackageHarness(harnessStamp.version, digest, harnessStamp.receiptId)
        val workflows = docs.map { doc ->
            val decision = state.lastDecision(doc.workflow.id)
            val workflow = doc.workflow
            val own = digestOf(doc)
            PackageWorkflow(workflow.id, workflow.name, workflow.status, own, decision?.by, decision?.receiptId)
        }
        val files = state.files.sortedWith(compareBy(JsText.localeOrder) { it.path })
        val context = if (state.context.isEmpty()) "" else (listOf(CONTEXT_HEADER) + state.context).joinToString("\n")
        val body = buildJsonObject {
            put("format", FORMAT)
            put("compiler", VERSION)
            put("repo", if (repo == null) JsonNull else JsonPrimitive(repo))
            put("server", inputs.serverUrl)
            put("harness", json.encodeToJsonElement(harness))
            put("workflows", json.encodeToJsonElement(workflows))
            put("nodes", json.encodeToJsonElement(state.nodes))
            put("hooks", json.encodeToJsonElement(state.hooks))
            put("scripts", json.encodeToJsonElement(state.scripts))
            put("notifications", json.encodeToJsonElement(state.notes))
            put("mcp", json.encodeToJsonElement(MCP))
            put("context", context)
            put("files", json.encodeToJsonElement(files))
        }
        return CompiledBody(
            repo,
            harness,
            picked,
            workflows,
            state.nodes,
            state.hooks,
            state.scripts,
            state.notes,
            files,
            body,
        )
    }

    // A workflow's digest: its nodes without their position, its edges and its status, as saved.
    private fun digestOf(doc: WorkflowDoc): String = Digests.digestOf(
        buildJsonObject {
            put(
                "nodes",
                buildJsonArray {
                    doc.raw.getValue("nodes").jsonArray.forEach { node ->
                        add(JsonObject(node.jsonObject.filterKeys { it != "position" }))
                    }
                },
            )
            put("edges", doc.raw.getValue("edges"))
            put("status", doc.workflow.status.wire)
        },
    )

    private val WorkflowStatus.wire: String get() = name.lowercase()

    private val WITH_SKILL = setOf(WorkflowNodeKind.STEP, WorkflowNodeKind.CHECK, WorkflowNodeKind.AGENT)

    private const val CONTEXT_HEADER = "[Ostrom] Workflows compiled into this session:"

    private val MCP = PackageMcp(
        servers = listOf(
            PackageMcpServer("ostrom", "/api/bridge/mcp"),
            PackageMcpServer("ostrom-workflow", "/api/compiler/mcp"),
        ),
        tools = listOf(
            PackageMcpTool("workflow_status", "Where this session stands in the compiled workflows", "ostrom-workflow"),
            PackageMcpTool("node_done", "Report that a workflow step (agent node) is done", "ostrom-workflow"),
        ),
    )

    // What the compilation of the workflows collects, in order.
    private class CompileState(private val inputs: CompileInputs, private val catalog: List<CatalogEntry>) {
        val nodes = mutableListOf<CompiledNode>()
        val hooks = mutableListOf<CompiledHook>()
        val scripts = mutableListOf<CompiledScript>()
        val notes = mutableListOf<CompiledNotify>()
        val files = mutableListOf<PackageFile>()
        val context = mutableListOf<String>()
        private var order = 0

        fun lastDecision(workflowId: String) = inputs.decisions
            .filter { it.subjectKind == "workflow" && it.subjectId == workflowId }
            .maxByOrNull { it.at }

        fun file(path: String, content: String, mode: PackageFileMode) {
            files += PackageFile(path, mode, Digests.sha256Hex(content.encodeToByteArray()), content)
        }

        private fun approvalOf(workflow: Workflow): ScriptApproval {
            val decision = lastDecision(workflow.id)
            val publishers = inputs.codePublishers
            return when {
                workflow.status == WorkflowStatus.PROPOSED ->
                    ScriptApproval(allowed = false, reason = "workflow proposed, not decided: shadow only")
                decision == null -> ScriptApproval(allowed = false, reason = "no signed decision on this workflow")
                else -> {
                    val allowed = publishers == null || decision.by.lowercase() in publishers.map { it.lowercase() }
                    ScriptApproval(
                        by = decision.by,
                        at = decision.at,
                        decisionId = decision.id,
                        receiptId = decision.receiptId,
                        allowed = allowed,
                        reason = if (allowed) {
                            "decided by a code publisher"
                        } else {
                            "${decision.by} may not publish code (OSTROM_CODE_PUBLISHERS)"
                        },
                    )
                }
            }
        }

        private fun gateClasses(nodeId: String): List<String> =
            catalog.filter { it.gate?.nodeId == nodeId }.map { it.action.cls }

        private fun patternsOf(classes: List<String>, extra: List<String>): List<String> {
            val out = LinkedHashSet(extra)
            for (cls in classes) {
                val entry = catalog.firstOrNull { it.action.cls == cls }
                val patterns = if (entry != null) {
                    entry.action.patterns
                } else {
                    ActionClasses.extra.firstOrNull { it.cls == cls }?.patterns.orEmpty()
                }
                out += patterns
            }
            return out.toList()
        }

        private fun compiledTrigger(node: WorkflowNode, workflow: Workflow, suggested: Boolean): CompiledTrigger {
            val trigger = NodeRules.triggerOf(node, workflow, suggested)
            // A linked gate (the only trigger with a gate node) without actions of its own: the catalog's classes.
            val gateNodeId = trigger.gateNodeId
            val classes =
                if (gateNodeId != null && trigger.classes.isEmpty()) gateClasses(gateNodeId) else trigger.classes
            val resolved = patternsOf(classes, trigger.patterns)
            return CompiledTrigger(trigger.from, trigger.gateNodeId, classes, trigger.patterns, resolved)
        }

        fun add(workflow: Workflow) {
            val mode = if (workflow.status == WorkflowStatus.PROPOSED) PackageMode.SHADOW else PackageMode.ENFORCE
            val approval = approvalOf(workflow)
            val ordered = orderOf(workflow)
            val compiled = ordered.map { node ->
                NodeBuild(node, workflow, mode, approval).also { it.compile() }.result()
            }
            val skillPath = "skills/ostrom-wf-${PackageTexts.slug(workflow.name)}/SKILL.md"
            // What a proposed workflow's steps say comes from a repository, not from a person: no skill is written
            // for it until it is accepted (its checks, gates and notifications stay shadow records).
            val skilled = workflow.status != WorkflowStatus.PROPOSED
            if (skilled) {
                val skill = PackageTexts.skill(workflow, ordered, compiled, scripts, notes, inputs.rules, inputs.repo)
                file(skillPath, skill, PackageFileMode.FILE)
            }
            nodes += compiled.map { node ->
                val withSkill = skilled && node.kind in WITH_SKILL
                if (withSkill) node.copy(artifacts = listOf(skillPath) + node.artifacts) else node
            }
            val trigger = workflow.nodes.firstOrNull { it.kind == WorkflowNodeKind.TRIGGER }
            val live = hooks.filter { it.workflowId == workflow.id && it.mode == PackageMode.ENFORCE }
            val proposed = if (workflow.status == WorkflowStatus.PROPOSED) " [proposed: logged only]" else ""
            val starts = trigger?.let { ", starts when: ${it.label}" }.orEmpty()
            val enforced = if (live.isEmpty()) {
                ""
            } else {
                "; enforced here: " + live.joinToString(", ") { hook ->
                    "${hook.kind.wire} \"${compiled.first { it.id == hook.nodeId }.label}\""
                }
            }
            val skillName = "ostrom-wf-${PackageTexts.slug(workflow.name)}"
            val skillNote = if (skilled) "; skill $skillName" else ""
            context += "- ${workflow.name}$proposed$starts$skillNote$enforced"
        }

        private val WorkflowNodeKind.wire: String get() = name.lowercase()

        // One node's compilation: its artifacts, hooks, script or notification.
        private inner class NodeBuild(
            private val node: WorkflowNode,
            private val workflow: Workflow,
            private var mode: PackageMode,
            private val approval: ScriptApproval,
        ) {
            private val artifacts = mutableListOf<String>()

            // The trigger of a check or a notification, shown in the package's summary.
            private var trigger: CompiledTrigger? = null

            fun compile() {
                when (node.kind) {
                    WorkflowNodeKind.CHECK -> compileCheck()
                    WorkflowNodeKind.GATE -> compileGate()
                    WorkflowNodeKind.NOTIFY -> compileNotify(triggered(suggested = false))
                    WorkflowNodeKind.AGENT -> compileAgent()
                    else -> Unit
                }
            }

            private fun triggered(suggested: Boolean): CompiledTrigger =
                compiledTrigger(node, workflow, suggested).also { trigger = it }

            // A check runs its command, or the analysis' suggestion while nobody wrote one (then in the shadow).
            private fun compileCheck() {
                val own = NodeRules.run(node).command.orEmpty()
                val suggestion = NodeRules.suggestions(node).command
                val suggested = suggestion?.takeIf {
                    own.isEmpty() && NodeRules.openSuggestion(node, SuggestionField.COMMAND)
                }
                val compiled = triggered(suggested = suggested != null)
                val command = own.ifEmpty { suggested?.value.orEmpty() }
                if (command.isNotEmpty()) compileScript(command, suggested, compiled)
            }

            private fun compileScript(command: String, suggested: StringSuggestion?, on: CompiledTrigger) {
                val run = NodeRules.run(node)
                val id = "s-${node.id}"
                val body = PackageTexts.script(workflow, node, command)
                val approved = suggested?.let {
                    ScriptApproval(
                        allowed = false,
                        reason = "suggested by the analysis (${it.evidence.firstOrNull() ?: "no proof"}): nobody has " +
                            "taken it yet",
                    )
                } ?: approval
                mode = if (mode == PackageMode.SHADOW || !approved.allowed) PackageMode.SHADOW else PackageMode.ENFORCE
                val script = CompiledScript(
                    id = id,
                    nodeId = node.id,
                    workflowId = workflow.id,
                    label = node.label,
                    where = run.where ?: RunLocation.WORKSTATION,
                    path = "scripts/$id.sh",
                    sha256 = Digests.sha256Hex(body.encodeToByteArray()),
                    command = command.trim(),
                    timeoutSec = (run.timeoutSec ?: DEFAULT_TIMEOUT).coerceIn(MIN_TIMEOUT, MAX_TIMEOUT),
                    network = run.network == true,
                    mode = mode,
                    approval = approved,
                    suggested = suggested?.let { ScriptSuggestion(it.evidence, it.excerpt) },
                )
                scripts += script
                file(script.path, body, PackageFileMode.EXECUTABLE)
                artifacts += script.path
                val resolved = on.resolved
                if (resolved.isNotEmpty()) {
                    hook(
                        kind = WorkflowNodeKind.CHECK,
                        event = AgentEvent.PRE_TOOL_USE,
                        tools = listOf("Bash"),
                        patterns = resolved,
                        classes = on.classes,
                        why = approved.reason.takeIf { mode == PackageMode.SHADOW },
                        scriptId = id,
                        suggested = suggested?.let { true },
                    )
                }
            }

            private fun compileGate() {
                val classes = gateClasses(node.id)
                val id = hook(
                    kind = WorkflowNodeKind.GATE,
                    event = AgentEvent.PRE_TOOL_USE,
                    tools = listOf("Bash", "mcp"),
                    patterns = patternsOf(classes, emptyList()),
                    classes = classes,
                    why = "no action of this repository falls under this gate".takeIf { classes.isEmpty() },
                    artifact = false,
                )
                artifacts += "hook $id (gate: ostrom-gate)"
            }

            private fun compileNotify(on: CompiledTrigger) {
                val run = NodeRules.run(node)
                val channel = run.channel.orEmpty()
                val suggestedChannel = NodeRules.suggestions(node).channel
                    ?.takeIf { channel.isEmpty() && NodeRules.openSuggestion(node, SuggestionField.CHANNEL) }
                val id = "n-${node.id}"
                mode = if (channel.isEmpty()) PackageMode.SHADOW else mode
                val note = CompiledNotify(
                    id = id,
                    nodeId = node.id,
                    workflowId = workflow.id,
                    channel = channel.ifEmpty { logOf(node.owner.orEmpty()) },
                    message = run.message?.ifEmpty { null }
                        ?: "${node.label}: {dev} ran \"{command}\" in {repo} (session {session}).",
                    mode = mode,
                    suggestedChannel = suggestedChannel?.let { SuggestedChannel(it.value, it.evidence) },
                )
                notes += note
                artifacts += "notification $id"
                val resolved = on.resolved
                if (resolved.isNotEmpty()) {
                    val why = when {
                        channel.isNotEmpty() -> null
                        suggestedChannel != null ->
                            "channel suggested by the analysis (${suggestedChannel.value}), not chosen by a person: " +
                                "logged only"
                        else -> "no channel chosen for this notification: logged only"
                    }
                    hook(
                        kind = WorkflowNodeKind.NOTIFY,
                        event = AgentEvent.POST_TOOL_USE,
                        tools = listOf("Bash"),
                        patterns = resolved,
                        classes = on.classes,
                        why = why,
                        notifyId = id,
                    )
                }
            }

            // Without a channel, a notification is logged under its owner, or the team's log.
            private fun logOf(owner: String): String = if (owner.isEmpty()) "log:team" else "log:$owner"

            // A proposed workflow's agents are not written: no sub-agent for what nobody accepted.
            private fun compileAgent() {
                if (workflow.status == WorkflowStatus.PROPOSED) return
                val claude = "agents/ostrom-${PackageTexts.slug(node.label)}.md"
                file(claude, PackageTexts.subagent(workflow, node), PackageFileMode.FILE)
                val codex = "codex/agents/${PackageTexts.codexAgentName(node.label)}.toml"
                file(codex, PackageTexts.codexAgent(workflow, node), PackageFileMode.FILE)
                artifacts += listOf(claude, codex)
            }

            @Suppress("LongParameterList")
            private fun hook(
                kind: WorkflowNodeKind,
                event: AgentEvent,
                tools: List<String>,
                patterns: List<String>,
                classes: List<String>,
                why: String?,
                scriptId: String? = null,
                notifyId: String? = null,
                suggested: Boolean? = null,
                artifact: Boolean = true,
            ): String {
                val id = "h-${node.id}"
                hooks += CompiledHook(
                    id = id,
                    nodeId = node.id,
                    workflowId = workflow.id,
                    kind = kind,
                    event = event,
                    tools = tools,
                    patterns = patterns,
                    classes = classes,
                    mode = mode,
                    why = why,
                    scriptId = scriptId,
                    notifyId = notifyId,
                    order = order++,
                    suggested = suggested,
                )
                if (artifact) artifacts += "hook $id"
                return id
            }

            fun result(): CompiledNode = CompiledNode(
                id = node.id,
                workflowId = workflow.id,
                workflowName = workflow.name,
                kind = node.kind,
                label = node.label,
                mode = mode,
                produces = NodeProduces.produces(node, workflow),
                trigger = trigger,
                artifacts = artifacts.toList(),
            )
        }
    }
}
