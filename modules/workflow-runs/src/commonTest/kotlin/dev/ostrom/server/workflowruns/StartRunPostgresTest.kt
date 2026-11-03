// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns

import dev.ostrom.core.api.models.RunStatus
import dev.ostrom.core.api.models.WorkflowRun
import dev.ostrom.server.compiler.CompilerModule
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.launches.LaunchesModule
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.runners.RunnersModule
import dev.ostrom.server.sessions.SessionsModule
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The runs of a workflow (contract `startWorkflowRun`, `listWorkflowRuns`, `listRuns`) on the real modules and
 * PostgreSQL, under the serving role: an owner or an admin starts a run of an accepted workflow with the inputs it
 * declares, any member reads them in both lists, page by page, and another organization neither sees nor starts them.
 */
class StartRunPostgresTest {
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        RunnersModule, LaunchesModule, WorkflowRunsModule,
    )

    // Tokens are `<kind>:<org letter>:<id>`: `user` a browser session, `cli` a person's token that writes workflows,
    // `reader` one that only reads them, `agent` a launch's agent token, `service` a service key of the organization.
    // The person's role is read from its id (`member`, `approver`, `admin`; anyone else owns the organization).
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore(':')) {
                    "user" -> AuthenticatedPrincipal(
                        token,
                        PrincipalKind.USER,
                        setOf(GateScopes.ALL_USER),
                        channel = PrincipalChannel.BROWSER,
                    )
                    "cli" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(READ, WRITE))
                    "reader" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf(READ))
                    "agent" -> AuthenticatedPrincipal(token, PrincipalKind.AGENT, setOf(GateScopes.AGENT_SESSION))
                    "service" -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, setOf(READ, WRITE))
                    else -> null
                }
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? {
                    val (kind, org, id) = principal.subjectId.split(":").takeIf { it.size == 3 } ?: return null
                    val orgId = if (org == "a") orgA else orgB
                    return when (kind) {
                        "agent" -> GateActor("agent", orgId, ActorKind.AGENT_SESSION, launchId = id, personId = "lead")
                        "service" -> GateActor(id, orgId, ActorKind.SERVICE)
                        else -> GateActor(id, orgId, ActorKind.USER, personId = id, role = roleOf(id))
                    }
                }
            }
        }
    }

    private fun runsTest(block: suspend (TestServer) -> Unit) = serverTest(modules, overrides) { block(it) }

    private suspend fun TestServer.call(token: String?, path: String, body: String? = null): HttpResponse =
        if (body == null) {
            client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
        } else {
            client.post(path) {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }

    /** An accepted workflow of organization A (or [org]'s), made by its owner's console; its version. */
    private suspend fun TestServer.workflow(
        id: String = SHIP,
        status: String = "accepted",
        org: String = "a",
        inputs: String = INPUTS,
    ): Int {
        val document = """{"id":"$id","name":"Ship $id","domain":"git","nodes":[{"id":"t","kind":"trigger",""" +
            """"label":"Push"}],"edges":[],"start":"t","status":"$status","foundAt":"2026-11-03T10:00:00Z",""" +
            """"inputs":$inputs}"""
        val made = call("user:$org:lead", "/api/v1/workflows", document)
        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        return made.json().getValue("version").jsonPrimitive.content.toInt()
    }

    private suspend fun HttpResponse.json(): JsonObject = OstromJson.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.run(): WorkflowRun =
        OstromJson.decodeFromString(WorkflowRun.serializer(), bodyAsText())

    private suspend fun HttpResponse.ids(): List<String> {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return json().getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
    }

    private suspend fun HttpResponse.next(): String? = (json()["next"] as? JsonPrimitive)?.content

    @Test
    fun anOwnerStartsARunOfAnAcceptedWorkflowWithItsInputsAndReadsItInBothLists() = runsTest { server ->
        val version = server.workflow()
        val started = server.call("user:a:lead", RUNS, """{"inputs":{"branch":"main"}}""")
        assertEquals(HttpStatusCode.Created, started.status, started.bodyAsText())
        val run = started.run()
        assertTrue(Regex("wfr_[0-9a-f]{32}").matches(run.id), run.id)
        assertEquals(
            listOf(SHIP, "Ship $SHIP", version.toString(), "lead", "manual"),
            listOf(run.workflowId, run.workflowName, run.workflowVersion.toString(), run.startedBy, run.source),
        )
        // No engine plays a run yet: it is queued, with nothing visited, no session, no end.
        assertEquals(RunStatus.QUEUED, run.status)
        assertEquals(emptyList(), run.visits)
        assertNull(run.sessionId)
        assertNull(run.endedAt)
        assertEquals(mapOf("branch" to "main", "note" to "none"), run.inputs, "The default of an input left out")
        Instant.parse(run.startedAt)

        assertEquals(listOf(run.id), server.call("user:a:lead", RUNS).ids())
        assertEquals(listOf(run.id), server.call("user:a:lead", "/api/v1/runs").ids())
        assertEquals(listOf(run.id), server.call("user:a:lead", "/api/v1/runs?status=queued").ids())
        assertEquals(emptyList(), server.call("user:a:lead", "/api/v1/runs?status=succeeded").ids())
        assertEquals(emptyList(), server.call("user:a:lead", "$RUNS?status=failed").ids())
        // The run as the lists answer it is the run as started.
        val listed = server.call("user:a:lead", "/api/v1/runs").json().getValue("items").jsonArray.single()
        assertEquals(OstromJson.encodeToJsonElement(WorkflowRun.serializer(), run), listed)
    }

    @Test
    fun anAdminOrAPersonsTokenStartsAMemberAnApproverAnAgentOrAServiceKeyNeither() = runsTest { server ->
        server.workflow()
        val body = """{"inputs":{"branch":"main"}}"""
        for (token in listOf("user:a:admin", "cli:a:lead")) {
            val started = server.call(token, RUNS, body)
            assertEquals(HttpStatusCode.Created, started.status, "$token ${started.bodyAsText()}")
        }
        for (token in listOf("user:a:member", "user:a:approver", "cli:a:member", "agent:a:l1", "service:a:ci")) {
            val refused = server.call(token, RUNS, body)
            assertEquals(HttpStatusCode.Forbidden, refused.status, "$token ${refused.bodyAsText()}")
        }
        // The role is checked before the workflow is looked up: a member learns nothing of which ids exist.
        val unknown = server.call("user:a:member", "/api/v1/workflows/absent/runs", body)
        assertEquals(HttpStatusCode.Forbidden, unknown.status)
        // A person who only reads workflows starts none.
        assertEquals(HttpStatusCode.Forbidden, server.call("reader:a:lead", RUNS, body).status)
        // Any member reads both lists; an agent token reads neither.
        for (token in listOf("user:a:member", "user:a:approver", "reader:a:member", "service:a:ci")) {
            assertEquals(2, server.call(token, RUNS).ids().size, token)
            assertEquals(2, server.call(token, "/api/v1/runs").ids().size, token)
        }
        assertEquals(HttpStatusCode.Forbidden, server.call("agent:a:l1", RUNS).status)
        assertEquals(HttpStatusCode.Forbidden, server.call("agent:a:l1", "/api/v1/runs").status)
        for ((path, body2) in listOf(RUNS to body, RUNS to null, "/api/v1/runs" to null)) {
            assertEquals(HttpStatusCode.Unauthorized, server.call(null, path, body2).status, path)
        }
    }

    @Test
    fun aRunTakesOnlyTheInputsItsWorkflowDeclaresOfAnAcceptedWorkflow() = runsTest { server ->
        server.workflow()
        val refused = listOf(
            """{"inputs":{"branch":"main","target":"prod"}}""" to "target",
            """{"inputs":{}}""" to "branch",
            """{}""" to "branch",
            """{"inputs":{"branch":"  "}}""" to "branch",
            """{"inputs":{"branch":"main","note":"far too long"}}""" to "note",
            """{"inputs":{"branch":"${"x".repeat(MAX_VALUE + 1)}"}}""" to "branch",
        )
        for ((body, input) in refused) {
            val answer = server.call("user:a:lead", RUNS, body)
            assertEquals(HttpStatusCode.BadRequest, answer.status, "$body ${answer.bodyAsText()}")
            assertTrue("inputs.$input" in answer.bodyAsText(), answer.bodyAsText())
        }
        val longest = server.call("user:a:lead", RUNS, """{"inputs":{"branch":"${"x".repeat(MAX_VALUE)}"}}""")
        assertEquals(HttpStatusCode.Created, longest.status, longest.bodyAsText())
        assertEquals(1, server.call("user:a:lead", RUNS).ids().size, "Only the run that was right started")

        // A workflow without inputs takes none; a proposed one runs only once accepted; an unknown one is not found.
        server.workflow("plain", inputs = "[]")
        assertEquals(HttpStatusCode.Created, server.call("user:a:lead", "/api/v1/workflows/plain/runs", "{}").status)
        server.workflow("idea", status = "proposed")
        val proposed = server.call("user:a:lead", "/api/v1/workflows/idea/runs", """{"inputs":{"branch":"main"}}""")
        assertEquals(HttpStatusCode.Conflict, proposed.status, proposed.bodyAsText())
        assertTrue("workflow_not_accepted" in proposed.bodyAsText(), proposed.bodyAsText())
        for (body in listOf("{}", null)) {
            val absent = server.call("user:a:lead", "/api/v1/workflows/absent/runs", body)
            assertEquals(HttpStatusCode.NotFound, absent.status, absent.bodyAsText())
        }
        for (path in listOf("/api/v1/runs?status=lost", "$RUNS?status=", "$RUNS?status=unknown")) {
            assertEquals(HttpStatusCode.BadRequest, server.call("user:a:lead", path).status, path)
        }
        val notJson = server.call("user:a:lead", RUNS, """{"inputs":{"branch":1}}""")
        assertEquals(HttpStatusCode.BadRequest, notJson.status, notJson.bodyAsText())
    }

    @Test
    fun anotherOrganizationNeitherSeesReadsNorStartsTheRuns() = runsTest { server ->
        server.workflow()
        val run = server.call("user:a:lead", RUNS, """{"inputs":{"branch":"main"}}""").run()
        // B has a workflow of the same id: its runs are its own.
        server.workflow(org = "b")
        assertEquals(emptyList(), server.call("user:b:lead", RUNS).ids())
        assertEquals(emptyList(), server.call("user:b:lead", "/api/v1/runs").ids())
        val own = server.call("user:b:lead", RUNS, """{"inputs":{"branch":"b"}}""").run()
        assertEquals(listOf(run.id), server.call("user:a:lead", "/api/v1/runs").ids(), "A sees none of B's")
        assertEquals(listOf(own.id), server.call("user:b:lead", "/api/v1/runs").ids(), "B sees none of A's")
        // A workflow only A has: not found for B, read or started.
        server.workflow("only-a")
        val path = "/api/v1/workflows/only-a/runs"
        assertEquals(HttpStatusCode.NotFound, server.call("user:b:lead", path).status)
        assertEquals(HttpStatusCode.NotFound, server.call("user:b:lead", path, """{"inputs":{"branch":"x"}}""").status)
        assertEquals(emptyList(), server.call("user:a:lead", path).ids(), "Nothing started in A by B")
        // B's cursor on A's run reads nothing of A.
        assertEquals(emptyList(), server.call("user:b:lead", "/api/v1/runs?before=${run.id}").ids())
    }

    @Test
    fun aPageEndsWithACursorAndTheNextPageHasTheOlderRuns() = runsTest { server ->
        server.workflow()
        server.workflow("other")
        val started = (1..PAGE + 1).map { i ->
            val path = if (i % 2 == 0) "/api/v1/workflows/other/runs" else RUNS
            server.call("user:a:lead", path, """{"inputs":{"branch":"b$i"}}""").run().id
        }
        val first = server.call("user:a:lead", "/api/v1/runs")
        assertEquals(PAGE, first.ids().size)
        val cursor = first.next()
        assertEquals(first.ids().last(), cursor, "A full page ends with the cursor of its last run")
        val second = server.call("user:a:lead", "/api/v1/runs?before=$cursor")
        assertEquals(1, second.ids().size)
        assertNull(second.next(), "A shorter page is the last")
        assertEquals(started.toSet(), (first.ids() + second.ids()).toSet())
        assertEquals(first.ids() + second.ids(), (first.ids() + second.ids()).distinct(), "No run twice")
        // Newest first, and the runs of a workflow are its own.
        val ofShip = server.call("user:a:lead", RUNS).ids()
        assertEquals(started.filterIndexed { i, _ -> i % 2 == 0 }.reversed(), ofShip)
        assertEquals(PAGE / 2, server.call("user:a:lead", "/api/v1/workflows/other/runs").ids().size)
        val queuedOfShip = server.call("user:a:lead", "$RUNS?status=queued&before=${ofShip.first()}").ids()
        assertEquals(ofShip.drop(1), queuedOfShip)
    }

    private fun roleOf(id: String): OrgRole = when (id) {
        "member" -> OrgRole.MEMBER
        "approver" -> OrgRole.APPROVER
        "admin" -> OrgRole.ADMIN
        else -> OrgRole.OWNER
    }

    private companion object {
        const val SHIP = "ship"
        const val RUNS = "/api/v1/workflows/$SHIP/runs"
        const val READ = "workflows:read"
        const val WRITE = "workflows:write"
        const val PAGE = 50
        const val MAX_VALUE = 4096
        const val INPUTS = """[{"id":"branch","label":"Branch","required":true},""" +
            """{"id":"note","label":"Note","default":"none","maxLength":8}]"""
    }
}
