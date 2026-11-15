// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns

import run.ostrom.core.api.Idempotency
import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.server.automations.AutomationsModule
import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.gates.presentation.GateScopes
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditEvent
import run.ostrom.server.identity.domain.AuditIntegrity
import run.ostrom.server.identity.domain.AuditLog
import run.ostrom.server.identity.domain.AuditRepository
import run.ostrom.server.identity.domain.AuditVerifier
import run.ostrom.server.identity.domain.OrgAuditQuery
import run.ostrom.server.identity.infrastructure.TransactionalAuditLog
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.launches.LaunchesModule
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.runners.RunnersModule
import run.ostrom.server.sessions.SessionsModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import run.ostrom.server.workflowruns.domain.repositories.RunsQuery
import run.ostrom.server.workflowruns.domain.models.RunLimitException
import run.ostrom.server.workflowruns.domain.models.RunNotFoundException
import run.ostrom.server.workflowruns.domain.models.RunWorkflowNotFoundException
import run.ostrom.server.workflowruns.domain.usecases.CancelWorkflowRunUseCase
import run.ostrom.server.workflowruns.domain.usecases.MAX_UNFINISHED
import run.ostrom.server.workflowruns.domain.usecases.MemberStart
import run.ostrom.server.workflowruns.domain.usecases.StartWorkflowRunUseCase
import run.ostrom.server.workflowruns.domain.usecases.StartedRun
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunChange
import run.ostrom.server.workflowruns.domain.models.RunGraph
import run.ostrom.server.workflowruns.domain.repositories.KeyedAdd
import run.ostrom.server.workflowruns.infrastructure.PostgresRunEngineRepository
import run.ostrom.server.workflowruns.infrastructure.PostgresStartedRunsRepository
import run.ostrom.server.workflowruns.infrastructure.keyedRun
import run.ostrom.server.workflowruns.infrastructure.runsPage
import run.ostrom.server.workflowruns.infrastructure.unfinishedRuns
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        RunnersModule, LaunchesModule, AutomationsModule, WorkflowRunsModule,
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

    private suspend fun TestServer.call(
        token: String?,
        path: String,
        body: String? = null,
        key: String? = null,
    ): HttpResponse =
        if (body == null) {
            client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
        } else {
            client.post(path) {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                key?.let { header(Idempotency.KEY_HEADER, it) }
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
        // The engine does not run in this test (RunEnginePostgresTest plays runs): queued, nothing visited, no end.
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
    fun aStartWithAnIdempotencyKeyReturnsTheFirstRunWhenItIsPressedAgain() = runsTest { server ->
        server.workflow()
        val body = """{"inputs":{"branch":"main"}}"""
        val first = server.call("user:a:lead", RUNS, body, key = "press-1")
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        assertNull(first.headers[Idempotency.REPLAYED_HEADER])
        val replay = server.call("user:a:lead", RUNS, body, key = "press-1")
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertEquals("true", replay.headers[Idempotency.REPLAYED_HEADER])
        assertEquals(first.run(), replay.run())
        assertEquals(1, server.call("user:a:lead", "/api/v1/runs").ids().size, "The replay started nothing")
        // Another key is another press; no key is an unkeyed start, as before.
        assertEquals(HttpStatusCode.Created, server.call("user:a:lead", RUNS, body, key = "press-2").status)
        assertEquals(HttpStatusCode.Created, server.call("user:a:lead", RUNS, body).status)
        assertEquals(3, server.call("user:a:lead", "/api/v1/runs").ids().size)
        // A key is its caller's: the admin's start under the same key is a run of the admin.
        val other = server.call("user:a:admin", RUNS, body, key = "press-1")
        assertEquals(HttpStatusCode.Created, other.status, other.bodyAsText())
        assertEquals("admin", other.run().startedBy)
    }

    @Test
    fun aKeyedStartIsRefusedForAMalformedKeyAndForWhoCannotStartBeforeItsKeyIsLookedUp() = runsTest { server ->
        server.workflow()
        val body = """{"inputs":{"branch":"main"}}"""
        for (key in listOf("has space", "a/b", "x".repeat(129))) {
            val refused = server.call("user:a:lead", RUNS, body, key = key)
            assertEquals(HttpStatusCode.BadRequest, refused.status, "$key ${refused.bodyAsText()}")
        }
        for (token in listOf("user:a:member", "user:a:approver", "agent:a:l1", "service:a:ci")) {
            assertEquals(HttpStatusCode.Forbidden, server.call(token, RUNS, body, key = "k").status, token)
        }
        assertEquals(emptyList(), server.call("user:a:lead", "/api/v1/runs").ids())
    }

    @Test
    fun aKeyedStartHonoursTheCapOfUnfinishedRunsAndAReplayStaysAnswerableAtTheCap() = runsTest { server ->
        server.workflow()
        val body = """{"inputs":{"branch":"main"}}"""
        repeat(MAX_UNFINISHED) {
            assertEquals(HttpStatusCode.Created, server.call("user:a:lead", RUNS, body, key = "k$it").status)
        }
        val limited = server.call("user:a:lead", RUNS, body, key = "one-more")
        assertEquals(HttpStatusCode.Conflict, limited.status, limited.bodyAsText())
        assertTrue("run_limit" in limited.bodyAsText(), limited.bodyAsText())
        val unkeyed = server.call("user:a:lead", RUNS, body)
        assertEquals(HttpStatusCode.Conflict, unkeyed.status, "A start without a key is held to the same cap")
        assertEquals(HttpStatusCode.OK, server.call("user:a:lead", RUNS, body, key = "k0").status)
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
        // Any member reads a workflow's runs and their own (TeamCode's `GET /workflows/runs`); an agent token reads
        // neither. Every member's runs are for an owner, an admin or a service key.
        for (token in listOf("user:a:member", "user:a:approver", "reader:a:member", "service:a:ci")) {
            assertEquals(2, server.call(token, RUNS).ids().size, token)
            assertEquals(emptyList(), server.call(token, "/api/v1/runs").ids(), token)
        }
        assertEquals(1, server.call("user:a:admin", "/api/v1/runs").ids().size, "The admin's own")
        for (token in listOf("user:a:admin", "service:a:ci")) {
            assertEquals(2, server.call(token, "/api/v1/runs?all=true").ids().size, token)
        }
        for (token in listOf("user:a:member", "user:a:approver", "reader:a:member")) {
            assertEquals(HttpStatusCode.Forbidden, server.call(token, "/api/v1/runs?all=true").status, token)
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
        // B's cursor on A's run reads nothing of A: it names no run of B's, a 400 (SRV-LECTURES).
        val replayed = server.call("user:b:lead", "/api/v1/runs?before=${run.id}")
        assertEquals(HttpStatusCode.BadRequest, replayed.status, replayed.bodyAsText())
        assertTrue(run.id !in replayed.bodyAsText(), "Nothing of A in the refusal")
    }

    @Test
    fun aPageEndsWithACursorAndTheNextPageHasTheOlderRuns() = runsTest { server ->
        server.workflow()
        server.workflow("other")
        // Three starters, as a member has at most MAX_UNFINISHED unfinished runs.
        val started = (1..PAGE + 1).map { i ->
            val path = if (i % 2 == 0) "/api/v1/workflows/other/runs" else RUNS
            server.call("user:a:lead${i % 3}", path, """{"inputs":{"branch":"b$i"}}""").run().id
        }
        val first = server.call("user:a:lead", "/api/v1/runs?all=true")
        assertEquals(PAGE, first.ids().size)
        val cursor = first.next()
        assertEquals(first.ids().last(), cursor, "A full page ends with the cursor of its last run")
        val second = server.call("user:a:lead", "/api/v1/runs?all=true&before=$cursor")
        assertEquals(1, second.ids().size)
        assertNull(second.next(), "A shorter page is the last")
        assertEquals(started.toSet(), (first.ids() + second.ids()).toSet())
        // A page size of the contract's bounds (1 to 100): a full page of one ends with its cursor; others are refused.
        val one = server.call("user:a:lead", "/api/v1/runs?all=true&limit=1")
        assertEquals(first.ids().take(1), one.ids())
        assertEquals(one.ids().single(), one.next())
        assertEquals(PAGE + 1, server.call("user:a:lead", "/api/v1/runs?all=true&limit=100").ids().size)
        for (limit in listOf(0, -1, 101, 100_000)) {
            val refused = server.call("user:a:lead", "/api/v1/runs?all=true&limit=$limit")
            assertEquals(HttpStatusCode.BadRequest, refused.status, "limit=$limit")
        }
        assertEquals(first.ids() + second.ids(), (first.ids() + second.ids()).distinct(), "No run twice")
        // Newest first, and the runs of a workflow are its own.
        val ofShip = server.call("user:a:lead", RUNS).ids()
        assertEquals(started.filterIndexed { i, _ -> i % 2 == 0 }.reversed(), ofShip)
        assertEquals(PAGE / 2, server.call("user:a:lead", "/api/v1/workflows/other/runs").ids().size)
        val queuedOfShip = server.call("user:a:lead", "$RUNS?status=queued&before=${ofShip.first()}").ids()
        assertEquals(ofShip.drop(1), queuedOfShip)
        // The organization's list narrowed to one workflow, and to the caller's own: lead0 started every third run.
        val allOfShip = server.call("user:a:lead", "/api/v1/runs?all=true&workflow=$SHIP&limit=100").ids()
        assertEquals(ofShip, allOfShip)
        val mine = started.filterIndexed { i, _ -> (i + 1) % 3 == 0 }.reversed()
        assertEquals(mine, server.call("user:a:lead0", "/api/v1/runs?limit=100").ids())
        val absent = server.call("user:a:lead", "/api/v1/runs?all=true&workflow=absent")
        assertEquals(HttpStatusCode.NotFound, absent.status, absent.bodyAsText())
    }

    @Test
    fun theRunQueriesUseTheirIndexes() = runsTest { server ->
        server.workflow()
        // Two starters, as a member has at most MAX_UNFINISHED unfinished runs.
        repeat(ROWS) { server.call("user:a:lead${it % 2}", RUNS, """{"inputs":{"branch":"b$it"}}""") }
        // Statistics only: the serving role owns no table, the owner analyzes it.
        server.database.owner.execute("ANALYZE workflow_runs_started").getOrThrow()
        // Each page is read in its order from an index: with sorting priced out, a page that needs a sort says so.
        val plans = listOf(
            Triple("runs of the organization", RunsQuery(), "workflow_runs_started_at"),
            Triple("runs of a workflow", RunsQuery(workflowId = SHIP), "_workflow"),
            Triple("runs by status", RunsQuery(status = RunStatus.QUEUED), "_status"),
            Triple("runs before a cursor", RunsQuery(before = "wfr_x"), "pkey"),
            Triple("runs of a starter", RunsQuery(startedBy = "lead0"), "_by"),
        )
        plans.forEach { (name, query, index) ->
            val plan = server.koin.get<JdbcDatabase>().withOrganization(orgA) {
                exec("SET LOCAL enable_seqscan = off")
                exec("SET LOCAL enable_bitmapscan = off")
                exec("SET LOCAL enable_sort = off")
                plan { runsPage(orgA, query).query }.also { rollback() }
            }
            println("-- $name\n$plan")
            assertTrue("Index" in plan && index in plan && "Sort" !in plan, "$name\n$plan")
        }
    }

    @Test
    fun aKeyedStartFindsItsFirstRunAndAMemberHasAtMostTwentyUnfinishedRuns() = runsTest { server ->
        server.workflow()
        val start = server.koin.get<StartWorkflowRunUseCase>()
        fun member(key: String, starter: String = "bob", org: Uuid = orgA) = MemberStart(
            org, starter, SHIP, mapOf("branch" to "main"), "acme/api", key, "automation", "auto-1",
        )
        val first = start.startFor(member("automation-run:1"))
        assertEquals(StartedRun(first.run, replayed = true), start.startFor(member("automation-run:1")))
        assertEquals("acme/api", first.run.repository)
        val other = start.startFor(member("automation-run:1", starter = "carol"))
        assertTrue(other.run.id != first.run.id, "A key is unique per starter")
        repeat(MAX_UNFINISHED - 1) { start.startFor(member("k$it")) }
        assertFailsWith<RunLimitException> { start.startFor(member("k-over")) }
        assertEquals(first.run, start.startFor(member("automation-run:1")).run, "A replay at the cap finds its run")
        val cancel = server.koin.get<CancelWorkflowRunUseCase>()
        assertFailsWith<RunNotFoundException> { cancel.cancelAsStarter(orgA, "carol", first.run.id) }
        assertEquals(RunStatus.CANCELLED, cancel.cancelAsStarter(orgA, "bob", first.run.id).status)
        assertFalse(start.startFor(member("k-after")).replayed, "An ended run frees a place")
        assertFailsWith<RunWorkflowNotFoundException> { start.startFor(member("k-b", org = orgB)) }
        server.database.owner.execute("ANALYZE workflow_runs_started").getOrThrow()
        val plans = listOf(
            "the run of a key" to { keyedRun(orgA, "bob", "k1").query },
            "the unfinished runs of a starter" to { unfinishedRuns(orgA, "bob", MAX_UNFINISHED) },
        )
        for ((name, query) in plans) {
            val plan = server.koin.get<JdbcDatabase>().withOrganization(orgA) {
                exec("SET LOCAL enable_seqscan = off")
                plan { query() }.also { rollback() }
            }
            println("-- $name\n$plan")
            assertTrue("workflow_runs_started_key" in plan || "workflow_runs_started_unfinished" in plan, plan)
            assertFalse("Seq Scan" in plan, plan)
        }
    }

    // The audit lines are signed between two transactions, on the chain's head: when another writer appends meanwhile,
    // the transaction that stores the run is rolled back and the lines are signed again on the new head. The run is
    // stored once, its line once, the chain stays one verified chain.
    @Test
    fun aChangeWhoseAuditHeadMovedWhileItWasSignedIsSignedAgainAndWrittenOnce() = runsTest { server ->
        val log = server.koin.get<AuditLog>()
        var races = 0
        val racing = TransactionalAuditLog(
            object : AuditLog by log {
                override fun sign(entry: AuditEntry): suspend (previousHash: String?, sequence: Long) -> AuditEvent {
                    val build = log.sign(entry)
                    return { previousHash, sequence ->
                        if (races > 0) log.record(AuditEntry(orgA, null, "meanwhile", "t", "${races--}"))
                        build(previousHash, sequence)
                    }
                }
            },
        )
        val database = server.koin.get<JdbcDatabase>()
        val runs = PostgresStartedRunsRepository(database, racing)
        val engine = PostgresRunEngineRepository(database, racing)
        val now = Instant.parse("2026-11-03T10:00:00Z")
        val run = WorkflowRun(
            id = "wfr_race", workflowId = SHIP, workflowName = "Ship", workflowVersion = 1, status = RunStatus.QUEUED,
            startedBy = "bob", source = "manual", inputs = emptyMap(), startedAt = now.toString(),
        )
        races = 1
        runs.add(orgA, run, now, RunGraph(), RunAudit("started"))
        val child = run.copy(id = "wfr_race_child", parentRunId = run.id)
        races = 1
        assertEquals(child.id, runs.addChild(orgA, child, 1, RunGraph(), RunAudit("child_started")))
        races = 1
        val keyed = run.copy(id = "wfr_race_keyed")
        assertEquals(KeyedAdd.Added(keyed), runs.addKeyed(orgA, keyed, now, RunGraph(), RunAudit("started"), "k", 5))
        val next = run.copy(status = RunStatus.RUNNING)
        races = 1
        assertTrue(engine.write(orgA, RunChange(run, next, null, audit = listOf(RunAudit("a"), RunAudit("b")))))
        assertTrue(engine.write(orgA, RunChange(next, run, null)), "A change with no audit line")
        assertFalse(engine.write(orgA, RunChange(next, run, null)), "Another change came first")
        assertEquals(run, runs.find(orgA, run.id))

        val lines = server.koin.get<AuditRepository>().listOrg(orgA, OrgAuditQuery(limit = 100))
        assertEquals(
            listOf("started", "child_started", "started", "a", "b").sorted(),
            lines.map { it.event.entry.action }.filter { it != "meanwhile" }.map { it.removePrefix("workflow_run.") }
                .sorted(),
        )
        assertEquals(4, lines.count { it.event.entry.action == "meanwhile" })
        val verifier = server.koin.get<AuditVerifier>()
        assertTrue(lines.all { verifier.integrity(it) == AuditIntegrity.VERIFIED })
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
        const val ROWS = 30
        const val MAX_VALUE = 4096
        const val INPUTS = """[{"id":"branch","label":"Branch","required":true},""" +
            """{"id":"note","label":"Note","default":"none","maxLength":8}]"""
    }
}
