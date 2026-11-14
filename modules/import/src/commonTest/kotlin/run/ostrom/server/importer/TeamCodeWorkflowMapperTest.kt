// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer

import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.domain.services.CallChecks
import run.ostrom.server.compiler.domain.services.PackageCompiler
import run.ostrom.server.compiler.domain.services.WorkflowChecks
import run.ostrom.server.importer.domain.MappedWorkflow
import run.ostrom.server.importer.domain.TeamCodeMapping
import run.ostrom.server.importer.domain.TeamCodeProfile
import run.ostrom.server.importer.domain.TeamCodeWorkflow
import run.ostrom.server.importer.domain.TeamCodeWorkflowMapper
import run.ostrom.server.importer.domain.ordered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TeamCodeWorkflowMapperTest {
    private val mapping = TeamCodeMapping(
        logins = mapOf("m1" to "lena"),
        profiles = listOf(
            TeamCodeProfile("p-api", "API", listOf("acme/api", "acme/lib"), setupCommands = 2),
            TeamCodeProfile("p-web", repositories = listOf("acme/web", "acme/docs")),
            TeamCodeProfile("p-one", repositories = listOf("acme/one")),
            TeamCodeProfile("p-none", "Empty"),
            TeamCodeProfile("p-setup", "Setup", listOf("acme/setup"), setupCommands = 1),
        ).associateBy { it.id },
    )

    private fun definition(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    /** A ticket fix: an agent fixes, a person reviews, a sub-workflow ships. */
    private val fix = TeamCodeWorkflow(
        id = "w1",
        name = "Fix a ticket",
        version = 4,
        updatedById = "m1",
        createdAt = 1_700_000_000_000,
        definition = definition(
            """
            {
              "description": "From ticket to release",
              "start": "fix",
              "sessionMode": "handoff",
              "inputs": [
                {"id": "ticket", "label": "Ticket"},
                {"id": "note", "label": "Note", "required": false, "default": "none", "maxLength": 200}
              ],
              "limits": {"maxNodes": 10, "maxVisits": 30, "maxDepth": 2},
              "nodes": [
                {
                  "id": "fix", "kind": "agent", "title": "Fix it",
                  "instruction": "Fix {{inputs.ticket}}", "profileId": "p-api", "agent": "claude", "model": "opus",
                  "deliverable": {"type": "url", "label": "The pull request", "domain": "github.com"},
                  "transitions": [{"on": "delivered", "to": "review"}], "maxVisits": 5
                },
                {
                  "id": "review", "kind": "human", "title": "Review it", "instruction": "Read {{nodes.fix.output}}",
                  "assignee": {"kind": "member", "memberId": "m1"},
                  "fields": [
                    {"id": "comment", "type": "text", "label": "Comment"},
                    {"id": "level", "type": "choice", "label": "Level", "options": [
                      {"value": "low", "label": "Low"}, {"value": "high", "label": "high"}
                    ]}
                  ],
                  "actions": [{"id": "approve", "label": "Approve", "style": "primary"}, {"id": "reject", "label": "Reject"}],
                  "transitions": [{"on": "approve", "to": "ship"}, {"on": "reject", "to": "fix"}]
                },
                {
                  "id": "ship", "kind": "workflow", "title": "Ship it", "workflowId": "w2",
                  "arguments": {"ticket": "{{inputs.ticket}}"}, "transitions": [{"on": "delivered"}]
                }
              ]
            }
            """,
        ),
    )

    private val ship = TeamCodeWorkflow(
        id = "w2",
        name = "Ship",
        version = 1,
        definition = definition(
            """
            {
              "start": "go",
              "inputs": [{"id": "ticket", "label": "Ticket"}],
              "nodes": [
                {
                  "id": "go", "kind": "agent", "title": "Release", "instruction": "Release {{inputs.ticket}}",
                  "profileId": "p-one", "deliverable": {"type": "text"}, "transitions": [{"on": "delivered"}]
                }
              ]
            }
            """,
        ),
    )

    private fun decoded(mapped: MappedWorkflow): Workflow =
        PackageCompiler.json.decodeFromJsonElement(Workflow.serializer(), assertNotNull(mapped.raw))

    @Test
    fun aTeamCodeWorkflowIsAnAcceptedOstromWorkflowThatTheSaveChecksAccept() {
        val mapped = TeamCodeWorkflowMapper.map(fix, mapping)
        val callee = TeamCodeWorkflowMapper.map(ship, mapping)
        val workflow = decoded(mapped)
        WorkflowChecks.check(workflow)
        WorkflowChecks.check(decoded(callee))
        CallChecks.check(workflow, mapOf("tc-w2" to decoded(callee)))

        assertEquals("tc-w1", workflow.id)
        assertEquals("Fix a ticket", workflow.name)
        assertEquals("TeamCode", workflow.domain)
        assertEquals(WorkflowStatus.ACCEPTED, workflow.status)
        assertEquals("teamcode", workflow.sources.single().kind)
        assertEquals("workflow/w1@v4", workflow.sources.single().ref)
        assertEquals("2023-11-14T22:13:20Z", workflow.foundAt)
        assertEquals("lena", workflow.updatedBy)
        assertEquals("handoff", workflow.sessionMode)
        assertEquals(listOf(10, 30, 2), workflow.limits?.let { listOf(it.maxNodes, it.maxVisits, it.maxDepth) })
        assertEquals(listOf(true, false), workflow.inputs.map { it.required })
        assertEquals(setOf("tc-w2"), mapped.calls)

        val (agent, wait, call) = workflow.nodes
        assertEquals(WorkflowNodeKind.AGENT, agent.kind)
        assertEquals("Fix {{inputs.ticket}}", agent.run?.instructions)
        assertEquals("acme/api", agent.run?.repository)
        assertEquals("github.com", agent.run?.deliverable?.domain)
        assertEquals(listOf("claude", "opus"), listOf(agent.run?.agent, agent.run?.model))
        assertEquals(5, agent.maxVisits)
        assertEquals(WorkflowNodeKind.WAIT, wait.kind)
        assertEquals(listOf("lena"), wait.approvers)
        assertEquals("Review it", wait.wait?.title)
        assertEquals("Read {{nodes.fix.output}}", wait.wait?.body)
        assertEquals(listOf("low", "high"), wait.wait?.fields?.last()?.options)
        assertEquals(listOf("approve", "reject"), wait.wait?.actions?.map { it.id })
        assertEquals(3, wait.maxVisits, "TeamCode's default")
        assertEquals(WorkflowNodeKind.WORKFLOW, call.kind)
        assertEquals("tc-w2", call.workflowId)
        assertEquals(mapOf("ticket" to "{{inputs.ticket}}"), call.arguments)

        assertEquals(
            listOf("fix>review:delivered", "review>ship:approve", "review>fix:reject", "ship>null:delivered"),
            workflow.edges.map { "${it.from}>${it.to}:${it.on}" },
        )
        val end = assertNotNull(mapped.raw)["edges"]?.jsonArray?.last()?.jsonObject
        assertEquals(JsonNull, end?.get("to"), "An edge that ends keeps its null target")
        assertEquals(JsonPrimitive(true), mapped.raw["inputs"]?.jsonArray?.first()?.jsonObject?.get("required"))
        assertEquals(
            listOf(
                "Node fix: only the first repository of its profile API is carried; its other repositories and setup " +
                    "commands belong to the organization's runner settings.",
                "Node review: the choices of level show their values, not their labels.",
            ),
            mapped.notes,
        )
        assertEquals(mapped, TeamCodeWorkflowMapper.map(fix, mapping), "The same mapping every run")
    }

    @Test
    fun anUnreadableOrOneTimeDefinitionIsNotImported() {
        val unreadable = TeamCodeWorkflowMapper.map(fix.copy(definition = definition("""{"start": "x"}""")), mapping)
        assertNull(unreadable.raw)
        assertEquals(listOf("TeamCode's definition could not be read."), unreadable.notes)

        val secret = fix.copy(
            definition = definition(
                """
                {"start": "a", "nodes": [
                  {"id": "a", "kind": "human", "title": "Token", "fields": [
                    {"id": "token", "type": "text", "label": "Token", "oneTime": true}
                  ]}
                ]}
                """,
            ),
        )
        val refused = TeamCodeWorkflowMapper.map(secret, mapping)
        assertNull(refused.raw)
        assertEquals(listOf("One-time fields (a.token) have no equivalent in Ostrom."), refused.notes)

        val looping = fix.copy(
            definition = definition("""{"start": "g", "nodes": [{"id": "g", "kind": "loop", "title": "G"}]}"""),
        )
        val unknown = TeamCodeWorkflowMapper.map(looping, mapping)
        assertNull(unknown.raw)
        assertEquals(listOf("Nodes of a kind Ostrom has no equivalent of (g: loop)."), unknown.notes)
    }

    @Test
    fun whatOstromCannotCarryIsNoted() {
        val odd = fix.copy(
            updatedById = null,
            createdAt = null,
            definition = definition(
                """
                {"start": "a", "nodes": [
                  {"id": "a", "kind": "agent", "title": "A", "instruction": "Go", "profileId": "p-gone",
                   "transitions": [{"on": "delivered", "to": "b"}]},
                  {"id": "b", "kind": "agent", "title": "B", "instruction": "Go", "profileId": "p-none",
                   "transitions": [{"on": "delivered", "to": "c"}]},
                  {"id": "c", "kind": "agent", "title": "C", "instruction": "Go", "profileId": "p-web",
                   "transitions": [{"on": "delivered", "to": "d"}]},
                  {"id": "d", "kind": "human", "title": "D", "assignee": {"kind": "member", "memberId": "m9"},
                   "fields": [{"id": "f", "type": "choice", "label": "F",
                     "options": [{"value": "x", "label": "x", "description": "Why"}]}],
                   "actions": [{"id": "ok", "label": "OK"}], "transitions": [{"on": "ok", "to": "e"}]},
                  {"id": "e", "kind": "human", "title": "E", "assignee": {"kind": "owner"},
                   "actions": [{"id": "ok", "label": "OK"}], "transitions": [{"on": "ok", "to": "f"}]},
                  {"id": "f", "kind": "workflow", "title": "F"}
                ]}
                """,
            ),
        )
        val mapped = TeamCodeWorkflowMapper.map(odd, mapping)
        val raw = assertNotNull(mapped.raw)
        assertEquals("1970-01-01T00:00:00Z", raw["foundAt"]?.let { (it as JsonPrimitive).content })
        assertNull(raw["updatedBy"])
        val workflow = decoded(mapped)
        assertEquals(listOf(null, null, "acme/web"), workflow.nodes.take(3).map { it.run?.repository })
        assertEquals(listOf(null, null), workflow.nodes.slice(3..4).map { it.approvers })
        assertNull(workflow.nodes[5].workflowId)
        assertEquals(emptySet(), mapped.calls)
        assertEquals(
            listOf(
                "Node a: its profile names no repository, so it works in none.",
                "Node b: its profile names no repository, so it works in none.",
                "Node c: only the first repository of its profile p-web is carried; its other repositories and setup " +
                    "commands belong to the organization's runner settings.",
                "Node d: its assignee has no Ostrom member, so the run's starter answers.",
                "Node d: the choices of f show their values, not their labels.",
            ),
            mapped.notes,
        )
    }

    @Test
    fun anUnassignedWaitHasNoApproverAndPlainChoicesOrASetupProfileAreNoted() {
        val plain = fix.copy(
            definition = definition(
                """
                {"start": "a", "nodes": [
                  {"id": "a", "kind": "human", "title": "A", "actions": [{"id": "ok", "label": "OK"}],
                   "fields": [{"id": "f", "type": "choice", "label": "F", "options": [{"value": "z", "label": "z"}]}],
                   "transitions": [{"on": "ok", "to": "b"}]},
                  {"id": "b", "kind": "human", "title": "B", "assignee": {"kind": "member"},
                   "fields": [{"id": "g", "type": "choice", "label": "G", "options": [{"value": "y", "label": "Yes"}]}],
                   "actions": [{"id": "ok", "label": "OK"}], "transitions": [{"on": "ok", "to": "c"}]},
                  {"id": "c", "kind": "agent", "title": "C", "instruction": "Go", "profileId": "p-setup"}
                ]}
                """,
            ),
        )
        val mapped = TeamCodeWorkflowMapper.map(plain, mapping)
        val workflow = decoded(mapped)
        assertEquals(listOf(null, null), workflow.nodes.take(2).map { it.approvers })
        assertEquals("acme/setup", workflow.nodes[2].run?.repository)
        assertEquals(
            listOf(
                "Node b: the choices of g show their values, not their labels.",
                "Node c: only the first repository of its profile Setup is carried; its other repositories and " +
                    "setup commands belong to the organization's runner settings.",
            ),
            mapped.notes,
        )
    }

    @Test
    fun aCalledWorkflowIsImportedBeforeItsCallersAndALoopKeepsItsOrder() {
        fun mapped(id: String, vararg calls: String) = MappedWorkflow(id, null, calls.toSet(), emptyList())
        val order = ordered(
            listOf(
                mapped("tc-a", "tc-c", "tc-gone"),
                mapped("tc-b"),
                mapped("tc-c", "tc-b"),
                mapped("tc-x", "tc-y"),
                mapped("tc-y", "tc-x"),
            ),
        )
        assertEquals(listOf("tc-b", "tc-c", "tc-a", "tc-y", "tc-x"), order.map { it.id })
    }
}
