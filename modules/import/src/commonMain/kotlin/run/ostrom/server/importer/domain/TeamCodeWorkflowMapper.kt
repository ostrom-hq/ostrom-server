// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.importer.domain

import run.ostrom.core.api.models.Deliverable
import run.ostrom.core.api.models.NodeRun
import run.ostrom.core.api.models.RuleSource
import run.ostrom.core.api.models.WaitAction
import run.ostrom.core.api.models.WaitField
import run.ostrom.core.api.models.WaitSpec
import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowEdge
import run.ostrom.core.api.models.WorkflowInput
import run.ostrom.core.api.models.WorkflowLimits
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.kernel.http.withNulls
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

/**
 * A TeamCode workflow as an Ostrom workflow document, or why it is not imported.
 *
 * @property id Its id in Ostrom, `tc-<TeamCode id>`.
 * @property raw The document, or null when it is not imported.
 * @property calls The Ostrom ids of the workflows it calls, imported first.
 * @property notes What the report says besides: what is carried differently, or why it is not imported.
 */
data class MappedWorkflow(val id: String, val raw: JsonObject?, val calls: Set<String>, val notes: List<String>)

/**
 * What the mapping reads besides the workflow.
 *
 * @property logins The Ostrom login of each mapped TeamCode member.
 * @property profiles TeamCode's profiles, by id.
 */
data class TeamCodeMapping(val logins: Map<String, String>, val profiles: Map<String, TeamCodeProfile>)

/**
 * Maps a TeamCode workflow to Ostrom's `Workflow` (docs/decisions/workflows-one-system.md, sections 1 and 5): an agent
 * node stays an agent node (its instruction, deliverable, agent and model under `run`, its profile's first repository
 * as `run.repository`), a human node becomes a wait node (its assignee, through the member map, the approver), a
 * workflow node calls the imported workflow, the transitions become edges. The workflow is `accepted`, its TeamCode
 * version in `sources`. Pure: a dry run and the import map the same.
 */
object TeamCodeWorkflowMapper {
    /** The Ostrom id of a TeamCode workflow. */
    fun idOf(teamCodeId: String): String = "$PREFIX$teamCodeId"

    /**
     * Maps one workflow.
     *
     * @param workflow The TeamCode workflow.
     * @param mapping The member map's logins and the profiles.
     * @return The document, or the reason it is not imported.
     */
    fun map(workflow: TeamCodeWorkflow, mapping: TeamCodeMapping): MappedWorkflow {
        val id = idOf(workflow.id)
        val definition = try {
            lenient.decodeFromJsonElement(Definition.serializer(), workflow.definition)
        } catch (expected: SerializationException) {
            // Never the parser's message: it may quote the definition.
            return MappedWorkflow(id, null, emptySet(), listOf(UNREADABLE))
        }
        val oneTime = definition.nodes.flatMap { node ->
            node.fields.filter { it.oneTime }.map { "${node.id}.${it.id}" }
        }
        if (oneTime.isNotEmpty()) {
            val note = "One-time fields (${oneTime.joinToString()}) have no equivalent in Ostrom."
            return MappedWorkflow(id, null, emptySet(), listOf(note))
        }
        val notes = mutableListOf<String>()
        val nodes = definition.nodes.map { node(it, mapping, notes) }
        val document = Workflow(
            id = id,
            name = workflow.name,
            domain = DOMAIN,
            description = definition.description,
            nodes = nodes,
            edges = definition.nodes.flatMap { node ->
                node.transitions.map { WorkflowEdge(from = node.id, to = it.to, on = it.on) }
            },
            start = definition.start,
            inputs = definition.inputs.map { WorkflowInput(it.id, it.label, it.required, it.default, it.maxLength) },
            sessionMode = definition.sessionMode,
            limits = definition.limits.let { WorkflowLimits(it.maxNodes, it.maxVisits, it.maxDepth) },
            sources = listOf(RuleSource(SOURCE_KIND, "workflow/${workflow.id}@v${workflow.version}")),
            status = WorkflowStatus.ACCEPTED,
            foundAt = Instant.fromEpochMilliseconds(workflow.createdAt ?: 0L).toString(),
            updatedBy = workflow.updatedById?.let { mapping.logins[it] },
        )
        val raw = documents.encodeToJsonElement(Workflow.serializer(), document).withNulls("edges.*.to").jsonObject
        return MappedWorkflow(id, raw, nodes.mapNotNull { it.workflowId }.toSet(), notes)
    }

    private fun node(node: Node, mapping: TeamCodeMapping, notes: MutableList<String>): WorkflowNode {
        val base = WorkflowNode(node.id, WorkflowNodeKind.UNKNOWN, node.title, maxVisits = node.maxVisits)
        return when (node.kind) {
            AGENT -> base.copy(kind = WorkflowNodeKind.AGENT, run = agentRun(node, mapping, notes))
            HUMAN -> base.copy(
                kind = WorkflowNodeKind.WAIT,
                approvers = approvers(node, mapping, notes),
                wait = WaitSpec(
                    title = node.title,
                    body = node.instruction,
                    fields = node.fields.map { field(node.id, it, notes) },
                    actions = node.actions.map { WaitAction(it.id, it.label, it.requires, it.style) },
                ),
            )
            WORKFLOW -> base.copy(
                kind = WorkflowNodeKind.WORKFLOW,
                workflowId = node.workflowId?.let(::idOf),
                arguments = node.arguments,
            )
            // An unknown kind stays unknown: the save refuses it, naming the node.
            else -> base
        }
    }

    private fun agentRun(node: Node, mapping: TeamCodeMapping, notes: MutableList<String>): NodeRun = NodeRun(
        instructions = node.instruction,
        agent = node.agent,
        model = node.model,
        deliverable = node.deliverable,
        repository = node.profileId?.let { repositoryOf(it, mapping.profiles, "Node ${node.id}", notes) },
    )

    private fun approvers(node: Node, mapping: TeamCodeMapping, notes: MutableList<String>): List<String>? {
        val member = node.assignee?.takeIf { it.kind == MEMBER }?.memberId ?: return null
        val login = mapping.logins[member]
        if (login == null) notes += "Node ${node.id}: its assignee has no Ostrom member, so the run's starter answers."
        return login?.let(::listOf)
    }

    private fun field(nodeId: String, field: Field, notes: MutableList<String>): WaitField {
        if (field.options.any { it.label != it.value || it.description != null }) {
            notes += "Node $nodeId: the choices of ${field.id} show their values, not their labels."
        }
        return WaitField(
            id = field.id,
            type = field.type,
            label = field.label,
            help = field.help,
            required = field.required,
            default = field.default,
            options = field.options.map { it.value },
            multiple = field.multiple,
            allowOther = field.allowOther,
            maxLength = field.maxLength,
        )
    }

    private val lenient = Json { ignoreUnknownKeys = true }
    private val documents = Json {
        explicitNulls = false
        encodeDefaults = true
    }
}

/**
 * The repository a TeamCode profile stands for: its first one. A profile the export lacks, or one without a
 * repository, gives none; one with more repositories or setup commands is noted, never silently cut.
 *
 * @param profileId The profile.
 * @param profiles The exported profiles.
 * @param what What names it, for the note.
 * @param notes Where the note goes.
 * @return The repository, or null.
 */
internal fun repositoryOf(
    profileId: String,
    profiles: Map<String, TeamCodeProfile>,
    what: String,
    notes: MutableList<String>,
): String? {
    val profile = profiles[profileId]
    val repository = profile?.repositories?.firstOrNull()
    if (profile == null || repository == null) {
        notes += "$what: its profile names no repository, so it works in none."
    } else if (profile.repositories.size > 1 || profile.setupCommands > 0) {
        val name = profile.name ?: profile.id
        notes += "$what: only the first repository of its profile $name is carried; its other repositories and " +
            "setup commands belong to the organization's runner settings."
    }
    return repository
}

private const val PREFIX = "tc-"
private const val DOMAIN = "TeamCode"
private const val SOURCE_KIND = "teamcode"
private const val AGENT = "agent"
private const val HUMAN = "human"
private const val WORKFLOW = "workflow"
private const val MEMBER = "member"
private const val UNREADABLE = "TeamCode's definition could not be read."

// TeamCode's stored definition (`WorkflowView` without its row fields), with TeamCode's defaults.

@Serializable
private class Definition(
    val description: String? = null,
    val start: String,
    val sessionMode: String = "same",
    val inputs: List<Input> = emptyList(),
    val nodes: List<Node>,
    val limits: Limits = Limits(),
)

@Serializable
private class Input(
    val id: String,
    val label: String,
    val required: Boolean = true,
    val default: String? = null,
    val maxLength: Int = 4096,
)

@Serializable
private class Limits(val maxNodes: Int = 50, val maxVisits: Int = 100, val maxDepth: Int = 3)

@Serializable
private class Node(
    val id: String,
    val kind: String,
    val title: String,
    val instruction: String? = null,
    val profileId: String? = null,
    val agent: String? = null,
    val model: String? = null,
    val deliverable: Deliverable? = null,
    val fields: List<Field> = emptyList(),
    val actions: List<Action> = emptyList(),
    val assignee: Assignee? = null,
    val workflowId: String? = null,
    val arguments: Map<String, String> = emptyMap(),
    val transitions: List<Transition> = emptyList(),
    val maxVisits: Int = 3,
)

@Serializable
private class Field(
    val id: String,
    val type: String,
    val label: String,
    val help: String? = null,
    val required: Boolean = false,
    val default: JsonElement? = null,
    val options: List<Option> = emptyList(),
    val multiple: Boolean = false,
    val allowOther: Boolean = false,
    val maxLength: Int? = null,
    val oneTime: Boolean = false,
)

@Serializable
private class Option(val value: String, val label: String, val description: String? = null)

@Serializable
private class Action(
    val id: String,
    val label: String,
    val requires: List<String> = emptyList(),
    val style: String? = null,
)

@Serializable
private class Assignee(val kind: String = "owner", val memberId: String? = null)

@Serializable
private class Transition(val on: String, val to: String? = null)
