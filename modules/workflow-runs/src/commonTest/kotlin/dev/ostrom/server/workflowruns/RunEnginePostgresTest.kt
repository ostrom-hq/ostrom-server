// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns

import dev.ostrom.core.api.models.NodeRun
import dev.ostrom.core.api.models.RunStatus
import dev.ostrom.core.api.models.WorkflowEdge
import dev.ostrom.core.api.models.WorkflowNode
import dev.ostrom.core.api.models.WorkflowNodeKind
import dev.ostrom.core.api.models.WorkflowRun
import dev.ostrom.server.compiler.CompilerModule
import dev.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.domain.models.Answer
import dev.ostrom.server.gates.domain.models.AnswerChannel
import dev.ostrom.server.gates.domain.models.AskedVia
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateStatus
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.launches.LaunchesModule
import dev.ostrom.server.launches.domain.usecases.OpenRunnerLaunchUseCase
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.receipts.domain.usecases.isoMillis
import dev.ostrom.server.runners.RunnersModule
import dev.ostrom.server.runners.domain.AgentKind
import dev.ostrom.server.runners.domain.CloudPlan
import dev.ostrom.server.runners.domain.CloudPlanProvider
import dev.ostrom.server.runners.domain.CloudSessionsRepository
import dev.ostrom.server.runners.domain.DesiredState
import dev.ostrom.server.runners.domain.FreeCloudPlanProvider
import dev.ostrom.server.runners.domain.SessionState
import dev.ostrom.server.sessions.SessionsModule
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.workflowruns.domain.engine.PlayRunUseCaseImpl
import dev.ostrom.server.workflowruns.domain.engine.RunMoves
import dev.ostrom.server.workflowruns.domain.engine.TickRunsUseCase
import dev.ostrom.server.workflowruns.domain.engine.TickRunsUseCaseImpl
import dev.ostrom.server.workflowruns.domain.engine.sessionOf
import dev.ostrom.server.workflowruns.domain.models.RunAudit
import dev.ostrom.server.workflowruns.domain.models.RunChange
import dev.ostrom.server.workflowruns.domain.models.RunGraph
import dev.ostrom.server.workflowruns.domain.repositories.RunEngineRepository
import dev.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import dev.ostrom.server.workflowruns.domain.services.RunAgents
import dev.ostrom.server.workflowruns.domain.services.RunGates
import dev.ostrom.server.workflowruns.fakes.FixedClock
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The engine that plays the runs of workflows, on the real modules and PostgreSQL under the serving role: an agent
 * node starts a cloud session of the run's starter, which the session's runner launches through the launch path; a
 * gate node opens an enforced gate and waits for its approver; the run ends `succeeded`, `failed` or `cancelled`, each
 * step written once, with its audit line, in its organization only.
 */
class RunEnginePostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        RunnersModule, LaunchesModule, WorkflowRunsModule,
    )

    // Cloud sessions are closed to every organization unless listed (OSTROM_RUNNERS_ALLOWED_ORGS): the free plan here.
    private val overrides = module { single<CloudPlanProvider> { FreeCloudPlanProvider() } }

    /** A member of a new organization, and a token of theirs that starts, cancels and reads the runs. */
    private class Person(val login: String, val org: Org, val userId: Uuid, val memberId: Uuid, val token: String)

    private val clock = FixedClock(Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds() + 1_000))

    private fun engineTest(block: suspend (TestServer) -> Unit) = serverTest(modules, overrides) { block(it) }

    private suspend fun TestServer.person(login: String, scopes: Set<String> = WRITE_SCOPES): Person {
        val now = clock.now()
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        val member = Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        val secret = TokenKind.PERSONAL.prefix + "_" + Uuid.random().toHexString()
        val token = ApiToken(
            Uuid.random(), user.id, org.id, "t", TokenKind.PERSONAL, scopes, "hint", null, now, now + 1.hours, null,
            null,
        )
        koin.get<ApiTokensRepository>().create(token, koin.get<SecretHasher>().hash(secret))
        return Person(login, org, user.id, member.id, secret)
    }

    private suspend fun TestServer.call(token: String, path: String, body: String? = null): HttpResponse =
        if (body == null) {
            client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
        } else {
            client.post(path) {
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }

    /** Saves an accepted workflow of [person]'s organization with these nodes and edges. */
    private suspend fun TestServer.workflow(person: Person, nodes: String, edges: String, start: String) {
        val document = """{"id":"$SHIP","name":"Ship","domain":"git","nodes":$nodes,"edges":$edges,""" +
            """"start":"$start","status":"accepted","foundAt":"2026-11-03T10:00:00Z",""" +
            """"inputs":[{"id":"branch","label":"Branch","required":true}]}"""
        val raw = OstromJson.parseToJsonElement(document).jsonObject
        koin.get<WorkflowsUseCase>().save(person.org.id, raw, null, person.login, true)
    }

    private suspend fun TestServer.start(person: Person): WorkflowRun {
        val started = call(person.token, "/api/v1/workflows/$SHIP/runs", """{"inputs":{"branch":"main"}}""")
        assertEquals(HttpStatusCode.Created, started.status, started.bodyAsText())
        return started.run()
    }

    private suspend fun TestServer.read(person: Person, id: String): WorkflowRun {
        val read = call(person.token, "/api/v1/runs/$id")
        assertEquals(HttpStatusCode.OK, read.status, read.bodyAsText())
        return read.run()
    }

    /** A queued run of [orgId], stored as a start stores it, with [graph]. */
    private suspend fun TestServer.queued(orgId: Uuid, graph: RunGraph, startedBy: String = "ada"): WorkflowRun {
        val now = clock.now() - 1.seconds
        val run = WorkflowRun(
            id = "wfr_" + Uuid.random().toHexString(),
            workflowId = SHIP,
            workflowName = "Ship",
            workflowVersion = 1,
            status = RunStatus.QUEUED,
            startedBy = startedBy,
            source = "manual",
            inputs = mapOf("branch" to "main"),
            startedAt = now.isoMillis(),
        )
        koin.get<StartedRunsRepository>().add(orgId, run, now, graph, RunAudit("started"))
        return run
    }

    private suspend fun TestServer.stored(orgId: Uuid, id: String): WorkflowRun =
        assertNotNull(koin.get<StartedRunsRepository>().find(orgId, id))

    /** An engine replica on the test's clock; each tick moves the clock past the look a waiting run asked for. */
    private fun TestServer.engine(owner: String = "engine-test"): TickRunsUseCase {
        val repository = koin.get<RunEngineRepository>()
        val play = PlayRunUseCaseImpl(repository, koin.get(), koin.get(), clock)
        return TickRunsUseCaseImpl(repository, play, clock, koin.get(), owner)
    }

    private suspend fun TickRunsUseCase.next(): Int {
        clock.now += 5.seconds
        return tick()
    }

    // The session ends as the runner reports it: no execution held any more.
    private suspend fun TestServer.endSession(orgId: Uuid, id: Uuid, state: SessionState = SessionState.PAUSED) {
        val sessions = koin.get<CloudSessionsRepository>()
        val session = assertNotNull(sessions.get(orgId, id))
        assertNotNull(sessions.compareAndSet(session.copy(state = state), null))
    }

    private suspend fun TestServer.answer(person: Person, gateId: Uuid, answer: Answer) {
        val gates = koin.get<GatesRepository>()
        val gate = assertNotNull(gates.get(person.org.id, gateId))
        val request = gates.requestsOf(person.org.id, gateId).first { it.approverId == person.login }
        val actor = GateActor.person(person.login, person.org.id, OrgRole.OWNER, Clock.System.now())
        koin.get<AnswerGateUseCase>()(actor, request.id, answer, gate.shownDigest, AnswerChannel.CONSOLE)
    }

    private suspend fun TestServer.audit(orgId: Uuid, runId: String): List<String> = database.superuser.fetchAll(
        "SELECT action FROM identity_audit_events WHERE org_id = CAST('$orgId' AS uuid) " +
            "AND target_type = 'workflow_run' AND target_id = '$runId' ORDER BY sequence",
    ).getOrThrow().map { it.get("action").asString() }

    private suspend fun TestServer.visitKeys(runId: String): Long = database.superuser.fetchAll(
        "SELECT COUNT(*) AS n FROM workflow_runs_visits WHERE run_id = '$runId'",
    ).getOrThrow().first().get("n").asString().toLong()

    private suspend fun HttpResponse.run(): WorkflowRun =
        OstromJson.decodeFromString(WorkflowRun.serializer(), bodyAsText())

    @Test
    fun anAgentNodeStartsACloudSessionOfTheStarterThatItsRunnerLaunchesThroughTheLaunchPath() = engineTest { server ->
        val ada = server.person("ada")
        server.workflow(ada, AGENT_THEN_GATE, AGENT_THEN_GATE_EDGES, "build")
        val run = server.start(ada)
        assertEquals(RunStatus.QUEUED, run.status)

        assertEquals(1, server.engine().next())

        val playing = server.read(ada, run.id)
        assertEquals(RunStatus.RUNNING, playing.status)
        assertEquals("build", playing.currentNodeId)
        val visit = playing.visits.single()
        assertEquals(
            listOf(1, "build", WorkflowNodeKind.AGENT, "running"),
            listOf(visit.seq, visit.nodeId, visit.kind, visit.status),
        )
        val sessionId = sessionOf(run.id, 1)
        assertEquals(sessionId.toString(), visit.sessionId)
        assertEquals(sessionId.toString(), playing.sessionId)
        val session = assertNotNull(server.koin.get<CloudSessionsRepository>().get(ada.org.id, sessionId))
        assertEquals(ada.memberId.toString(), session.ownerId)
        assertEquals(AgentKind.CLAUDE, session.agent)
        assertEquals("Fix the build of main", session.prompt)
        assertEquals("claude-opus-5-5", session.model)
        assertEquals("Ship · Build", session.name)
        assertEquals(DesiredState.RUNNING, session.desiredState)
        // The session's runner opens its launch as for any cloud session: for the starter, under the policy.
        val launch = server.koin.get<OpenRunnerLaunchUseCase>()(session).launch
        assertEquals(
            listOf<Any?>(ada.userId, "claude-code", sessionId),
            listOf(launch.userId, launch.agent, launch.cloudSessionId),
        )
        // While the agent works, the run waits for it and the session is not started twice.
        assertEquals(1, server.engine().next())
        assertEquals(RunStatus.RUNNING, server.read(ada, run.id).status)
        assertEquals(
            listOf("workflow_run.started", "workflow_run.node_started"),
            server.audit(ada.org.id, run.id),
        )
    }

    @Test
    fun aGateNodeOpensAnEnforcedGateOfItsApproversOnceTheAgentEnded() = engineTest { server ->
        val ada = server.person("ada")
        server.workflow(ada, AGENT_THEN_GATE, AGENT_THEN_GATE_EDGES, "build")
        val run = server.start(ada)
        val engine = server.engine()
        engine.next()
        server.endSession(ada.org.id, sessionOf(run.id, 1))

        engine.next()

        val waiting = server.read(ada, run.id)
        assertEquals(RunStatus.WAITING, waiting.status)
        assertEquals("review", waiting.currentNodeId)
        val (agent, gate) = waiting.visits
        assertEquals(listOf("done", "next"), listOf(agent.status, agent.outcome))
        assertEquals(
            listOf(2, "review", WorkflowNodeKind.GATE, "waiting"),
            listOf(gate.seq, gate.nodeId, gate.kind, gate.status),
        )
        val gateId = Uuid.parse(assertNotNull(gate.gateId))
        val opened = assertNotNull(server.koin.get<GatesRepository>().get(ada.org.id, gateId))
        assertEquals(GateStatus.PENDING, opened.status)
        assertEquals(setOf(AskedVia.WORKFLOW), opened.askedVia)
        assertEquals(listOf("ada"), opened.askedBy)
        assertEquals(run.id, opened.subject.runId)
        val node = assertNotNull(opened.node)
        assertEquals(listOf<Any>(SHIP, "review", true), listOf(node.workflowId, node.nodeId, node.enforced))
        val approvers = server.koin.get<GatesRepository>().requestsOf(ada.org.id, gateId).map { it.approverId }
        assertEquals(listOf("ada"), approvers)
        // A look while nobody answered changes nothing: the same gate, still waiting.
        engine.next()
        assertEquals(waiting, server.read(ada, run.id))
        assertEquals(
            listOf(
                "workflow_run.started", "workflow_run.node_started", "workflow_run.node_started",
                "workflow_run.gate_opened",
            ),
            server.audit(ada.org.id, run.id),
        )
    }

    @Test
    fun anAgentThenAGateApprovedEndsTheRunSucceeded() = engineTest { server ->
        val ada = server.person("ada")
        server.workflow(ada, AGENT_THEN_GATE, AGENT_THEN_GATE_EDGES, "build")
        val run = server.start(ada)
        val engine = server.engine()
        engine.next()
        server.endSession(ada.org.id, sessionOf(run.id, 1))
        engine.next()
        val gateId = Uuid.parse(assertNotNull(server.read(ada, run.id).visits.last().gateId))

        server.answer(ada, gateId, Answer.YES)
        engine.next()

        val ended = server.read(ada, run.id)
        assertEquals(RunStatus.SUCCEEDED, ended.status)
        assertNull(ended.currentNodeId)
        assertNull(ended.failure)
        assertNotNull(ended.endedAt)
        assertEquals(listOf("next", "approved"), ended.visits.map { it.outcome })
        assertEquals(listOf("done", "done"), ended.visits.map { it.status })
        assertEquals(2L, server.visitKeys(run.id))
        assertEquals(
            listOf(
                "workflow_run.started", "workflow_run.node_started", "workflow_run.node_started",
                "workflow_run.gate_opened", "workflow_run.ended",
            ),
            server.audit(ada.org.id, run.id),
        )
        // Nothing is left to play: no replica leases it again.
        assertEquals(0, engine.next())
        assertEquals(ended, server.read(ada, run.id))
    }

    @Test
    fun aGateRejectedFailsTheRunWhenNoEdgeTakesTheNo() = engineTest { server ->
        val ada = server.person("ada")
        server.workflow(ada, GATE_ONLY, "[]", "review")
        val run = server.start(ada)
        val engine = server.engine()
        engine.next()
        server.answer(ada, Uuid.parse(assertNotNull(server.read(ada, run.id).visits.single().gateId)), Answer.NO)

        engine.next()

        val ended = server.read(ada, run.id)
        assertEquals(RunStatus.FAILED, ended.status)
        assertEquals("Node review: rejected.", ended.failure)
        assertEquals("rejected", ended.visits.single().outcome)
    }

    @Test
    fun aRunEndsWhenItsAgentFailsOrTheGateIsRefusedOrItsNodeIsNotPlayedYet() = engineTest { server ->
        val ada = server.person("ada")
        val engine = server.engine()
        val failedAgent = server.queued(ada.org.id, RunGraph("a", listOf(AGENT)))
        val noApprover = server.queued(ada.org.id, RunGraph("g", listOf(WorkflowNode("g", WorkflowNodeKind.GATE, "G"))))
        val check = server.queued(ada.org.id, RunGraph("c", listOf(WorkflowNode("c", WorkflowNodeKind.CHECK, "C"))))
        val legacy = server.queued(ada.org.id, RunGraph())
        // A run started before the server played runs has no graph at all.
        server.database.superuser.fetchAll(
            "UPDATE workflow_runs_started SET graph = NULL WHERE id = '${legacy.id}' RETURNING id",
        ).getOrThrow()
        val ghost = server.queued(ada.org.id, RunGraph("a", listOf(AGENT)), startedBy = "ghost")
        engine.next()
        server.endSession(ada.org.id, sessionOf(failedAgent.id, 1), SessionState.FAILED)

        engine.next()

        suspend fun ended(run: WorkflowRun) = server.stored(ada.org.id, run.id)
        assertEquals("Node a: failed (The agent session failed to start.).", ended(failedAgent).failure)
        assertEquals(RunStatus.FAILED, ended(noApprover).status)
        assertTrue(ended(noApprover).visits.single().output.orEmpty().isNotEmpty())
        assertEquals("Node c: failed (The server does not play check nodes yet.).", ended(check).failure)
        assertEquals(RunMoves.NOTHING_TO_PLAY, ended(legacy).failure)
        assertEquals(
            "Node a: failed (The person who started the run is no longer a member of the organization.).",
            ended(ghost).failure,
        )
    }

    @Test
    fun aPlanThatRefusesTheSessionFailsTheAgentNode() = serverTest(
        modules,
        module { single<CloudPlanProvider> { PlanOf(CloudPlan(0, 300, 30, 240, true, true)) } },
    ) { server ->
        val ada = server.person("ada")
        val run = server.queued(ada.org.id, RunGraph("a", listOf(AGENT)))

        server.engine().next()

        val ended = server.stored(ada.org.id, run.id)
        assertEquals(RunStatus.FAILED, ended.status)
        assertTrue(ended.visits.single().output.orEmpty().isNotEmpty(), ended.toString())
        assertNull(server.koin.get<CloudSessionsRepository>().get(ada.org.id, sessionOf(run.id, 1)))
    }

    @Test
    fun aNodeVisitedMoreThanItsMaxVisitsFailsTheRun() = engineTest { server ->
        val ada = server.person("ada")
        val once = server.queued(ada.org.id, RunGraph("t", listOf(TRIGGER), listOf(WorkflowEdge("t", "t"))))
        val thrice = server.queued(
            ada.org.id,
            RunGraph("t", listOf(TRIGGER.copy(maxVisits = 3)), listOf(WorkflowEdge("t", "t", on = "next"))),
        )
        val ends = server.queued(ada.org.id, RunGraph("t", listOf(TRIGGER), listOf(WorkflowEdge("t", null))))
        val missing = server.queued(ada.org.id, RunGraph("t", listOf(TRIGGER), listOf(WorkflowEdge("t", "nowhere"))))

        server.engine().next()

        assertEquals("Node t was visited 1 time(s), its maxVisits.", server.stored(ada.org.id, once.id).failure)
        val looped = server.stored(ada.org.id, thrice.id)
        assertEquals("Node t was visited 3 time(s), its maxVisits.", looped.failure)
        assertEquals(3, looped.visits.size)
        assertEquals(3L, server.visitKeys(thrice.id))
        assertEquals(RunStatus.SUCCEEDED, server.stored(ada.org.id, ends.id).status)
        assertEquals("Node nowhere is not in the workflow.", server.stored(ada.org.id, missing.id).failure)
    }

    @Test
    fun anOwnerCancelsARunAndTheEngineEndsItsAgentSession() = engineTest { server ->
        val ada = server.person("ada")
        val reader = server.person("rex", setOf("workflows:read"))
        server.workflow(ada, AGENT_THEN_GATE, AGENT_THEN_GATE_EDGES, "build")
        val run = server.start(ada)
        val engine = server.engine()
        engine.next()

        val refused = server.call(reader.token, "/api/v1/runs/${run.id}/cancel", "{}")
        assertEquals(HttpStatusCode.Forbidden, refused.status, refused.bodyAsText())
        val cancelled = server.call(ada.token, "/api/v1/runs/${run.id}/cancel", "{}")
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        val answer = cancelled.run()
        assertEquals(RunStatus.CANCELLED, answer.status)
        assertNull(answer.currentNodeId)
        assertNotNull(answer.endedAt)
        assertEquals(listOf("failed", "cancelled"), answer.visits.single().let { listOf(it.status, it.outcome) })
        assertEquals(answer, server.read(ada, run.id))

        assertEquals(1, engine.next())

        val session = assertNotNull(server.koin.get<CloudSessionsRepository>().get(ada.org.id, sessionOf(run.id, 1)))
        assertEquals(DesiredState.TERMINATED, session.desiredState)
        assertEquals(answer, server.read(ada, run.id))
        assertEquals(0, engine.next())
        val again = server.call(ada.token, "/api/v1/runs/${run.id}/cancel", "{}")
        assertEquals(HttpStatusCode.Conflict, again.status, again.bodyAsText())
        assertEquals("workflow_run.cancelled", server.audit(ada.org.id, run.id).last())
    }

    @Test
    fun anotherOrganizationNeitherReadsNorCancelsARun() = engineTest { server ->
        val ada = server.person("ada")
        val eve = server.person("eve")
        server.workflow(ada, GATE_ONLY, "[]", "review")
        val run = server.start(ada)

        assertEquals(HttpStatusCode.NotFound, server.call(eve.token, "/api/v1/runs/${run.id}").status)
        assertEquals(HttpStatusCode.NotFound, server.call(eve.token, "/api/v1/runs/${run.id}/cancel", "{}").status)
        assertEquals(HttpStatusCode.NotFound, server.call(ada.token, "/api/v1/runs/wfr_unknown").status)
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/runs/${run.id}").status)
        assertEquals(RunStatus.QUEUED, server.read(ada, run.id).status)
        // The engine plays each organization's runs in its own: Eve's organization holds no visit, no gate of Ada's.
        server.engine().next()
        assertEquals(RunStatus.WAITING, server.read(ada, run.id).status)
        assertEquals(HttpStatusCode.NotFound, server.call(eve.token, "/api/v1/runs/${run.id}").status)
    }

    @Test
    fun aRunIsLeasedToOneReplicaAndAChangeIsWrittenOnlyOnTheRunAsRead() = engineTest { server ->
        val ada = server.person("ada")
        val run = server.queued(ada.org.id, RunGraph("t", listOf(TRIGGER)))
        val repository = server.koin.get<RunEngineRepository>()
        val now = clock.now()

        val first = repository.claimDue("one", now, now + 60.seconds, 10)
        assertEquals(listOf(run.id), first.map { it.id })
        assertEquals(emptyList(), repository.claimDue("two", now, now + 60.seconds, 10))
        val begin = RunMoves("one", now).start(run, assertNotNull(repository.played(ada.org.id, run.id)).graph)
        // Another replica's change, a change of a run that moved, and a second begin of the same visit are dropped.
        assertFalse(repository.write(ada.org.id, begin.copy(owner = "two")))
        assertFalse(repository.write(ada.org.id, begin.copy(expected = begin.next)))
        assertFalse(repository.write(Uuid.random(), begin))
        assertTrue(repository.write(ada.org.id, begin))
        assertFalse(repository.write(ada.org.id, RunChange(begin.next, begin.next, "one", begin.visit)))
        assertEquals(1L, server.visitKeys(run.id))
        // A lease that lapsed (the replica stopped) is taken by another, which goes on from the stored run.
        repository.release(ada.org.id, run.id, "two", null)
        assertEquals(listOf(run.id), repository.claimDue("two", now + 61.seconds, now + 120.seconds, 10).map { it.id })
        repository.release(ada.org.id, run.id, "two", null)
        assertEquals(emptyList(), repository.claimDue("one", now + 200.seconds, now + 260.seconds, 10))
        assertNull(repository.played(eveOrg, run.id))
        // A gate or a session another organization holds, or none, is not there for this one.
        assertNull(server.koin.get<RunGates>().state(ada.org.id, Uuid.random()))
        assertNull(server.koin.get<RunAgents>().state(eveOrg, sessionOf(run.id, 1)))
    }

    @Test
    fun twoReplicasPlayingTheSameRunsBeginEachVisitOnce() = engineTest { server ->
        val ada = server.person("ada")
        val graph = RunGraph(
            "t",
            listOf(TRIGGER.copy(maxVisits = 5), WorkflowNode("u", WorkflowNodeKind.TRIGGER, "U")),
            listOf(WorkflowEdge("t", "t"), WorkflowEdge("t", "u", on = "never")),
        )
        val runs = List(RUNS) { server.queued(ada.org.id, graph) }

        val leased = coroutineScope {
            listOf("one", "two").map { owner -> async { server.engine(owner).tick() } }.awaitAll()
        }

        assertEquals(RUNS, leased.sum())
        runs.forEach { run ->
            val ended = server.stored(ada.org.id, run.id)
            assertEquals(RunStatus.FAILED, ended.status)
            assertEquals(5, ended.visits.size)
            assertEquals(5L, server.visitKeys(run.id))
        }
    }

    @Test
    fun theModuleStartsTheEngineThatPlaysAStartedRun() = engineTest { server ->
        val ada = server.person("ada")
        server.workflow(ada, GATE_ONLY, "[]", "review")
        val run = server.start(ada)
        val job = Job()

        WorkflowRunsModule.start(CoroutineScope(job), server.koin)

        try {
            withTimeout(20.seconds) {
                while (server.read(ada, run.id).status != RunStatus.WAITING) delay(50.milliseconds)
            }
        } finally {
            job.cancel()
        }
        val gateId = Uuid.parse(assertNotNull(server.read(ada, run.id).visits.single().gateId))
        assertEquals(GateStatus.PENDING, server.koin.get<GatesRepository>().get(ada.org.id, gateId)?.status)
    }

    /** A plan provider that answers one plan. */
    private class PlanOf(private val plan: CloudPlan) : CloudPlanProvider {
        override suspend fun planFor(orgId: Uuid): CloudPlan = plan
    }

    private companion object {
        const val SHIP = "ship"
        const val RUNS = 20
        val WRITE_SCOPES = setOf("workflows:read", "workflows:write")
        val eveOrg: Uuid = Uuid.parse("0000000e-0000-0000-0000-000000000000")
        val TRIGGER = WorkflowNode("t", WorkflowNodeKind.TRIGGER, "Push")
        val AGENT = WorkflowNode("a", WorkflowNodeKind.AGENT, "Agent", run = NodeRun(instructions = "Do it"))
        const val AGENT_THEN_GATE = """[{"id":"build","kind":"agent","label":"Build",""" +
            """"run":{"instructions":"Fix the build of {{inputs.branch}}","agent":"claude",""" +
            """"model":"claude-opus-5-5"}},""" +
            """{"id":"review","kind":"gate","label":"Review","approvers":["ada"]}]"""
        const val AGENT_THEN_GATE_EDGES =
            """[{"from":"build","to":"review"},{"from":"review","to":null,"on":"approved"}]"""
        const val GATE_ONLY = """[{"id":"review","kind":"gate","label":"Review","approvers":["ada"]}]"""
    }
}
