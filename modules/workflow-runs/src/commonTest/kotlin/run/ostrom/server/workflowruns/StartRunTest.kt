// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns

import run.ostrom.core.api.models.RunStatus
import run.ostrom.core.api.models.WorkflowInput
import run.ostrom.core.api.models.WorkflowRun
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.sessions.CloudProfileDirectory
import run.ostrom.server.kernel.sessions.NamedCloudProfile
import run.ostrom.server.kernel.errors.InvalidPageException
import run.ostrom.server.workflowruns.domain.models.InvalidRunException
import run.ostrom.server.workflowruns.domain.models.RunAudit
import run.ostrom.server.workflowruns.domain.models.RunGraph
import run.ostrom.server.workflowruns.domain.models.RunWorkflow
import run.ostrom.server.workflowruns.domain.models.RunWorkflowNotFoundException
import run.ostrom.server.workflowruns.domain.models.StartRunForbiddenException
import run.ostrom.server.workflowruns.domain.models.WorkflowNotAcceptedException
import run.ostrom.server.workflowruns.domain.models.RunLimitException
import run.ostrom.server.workflowruns.domain.repositories.KeyedAdd
import run.ostrom.server.workflowruns.domain.repositories.KeyedRunsRepository
import run.ostrom.server.workflowruns.domain.repositories.RunsQuery
import run.ostrom.server.workflowruns.domain.repositories.StartedRunsRepository
import run.ostrom.server.workflowruns.domain.services.RunWorkflows
import run.ostrom.server.workflowruns.domain.usecases.ListWorkflowRunsUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.RunInputs
import run.ostrom.server.workflowruns.domain.usecases.MAX_UNFINISHED
import run.ostrom.server.workflowruns.domain.usecases.MemberStart
import run.ostrom.server.workflowruns.domain.usecases.StartWorkflowRunUseCaseImpl
import run.ostrom.server.workflowruns.domain.usecases.StartedRun
import run.ostrom.server.workflowruns.fakes.FixedClock
import run.ostrom.server.workflowruns.fakes.ORG
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class StartRunTest {
    private class InMemoryStartedRuns : StartedRunsRepository, KeyedRunsRepository {
        val stored = mutableListOf<Triple<Uuid, WorkflowRun, Instant>>()

        val audits = mutableListOf<RunAudit>()

        val keys = mutableMapOf<Pair<String, String>, WorkflowRun>()

        override suspend fun addKeyed(
            orgId: Uuid,
            run: WorkflowRun,
            startedAt: Instant,
            graph: RunGraph,
            audit: RunAudit,
            key: String,
            maxUnfinished: Int,
        ): KeyedAdd {
            val existing = keys[run.startedBy to key]
            val unfinished = stored.count { it.second.startedBy == run.startedBy }
            return when {
                existing != null -> KeyedAdd.Existing(existing)
                unfinished >= maxUnfinished -> KeyedAdd.AtLimit
                else -> {
                    add(orgId, run, startedAt, graph, audit)
                    keys[run.startedBy to key] = run
                    KeyedAdd.Added(run)
                }
            }
        }

        override suspend fun add(orgId: Uuid, run: WorkflowRun, startedAt: Instant, graph: RunGraph, audit: RunAudit) {
            stored += Triple(orgId, run, startedAt)
            audits += audit
        }

        override suspend fun addChild(orgId: Uuid, child: WorkflowRun, seq: Int, graph: RunGraph, audit: RunAudit) =
            error("A start stores no child run.")

        override suspend fun find(orgId: Uuid, id: String): WorkflowRun? =
            stored.firstOrNull { it.first == orgId && it.second.id == id }?.second

        // Newest first, and after a cursor only the older ones: nothing after a run it does not hold, as the database.
        override suspend fun runs(orgId: Uuid, query: RunsQuery): List<WorkflowRun> {
            val newest = stored.filter { it.first == orgId }.map { it.second }.reversed()
            val older = query.before?.let { before ->
                val at = newest.indexOfFirst { it.id == before }
                if (at < 0) emptyList() else newest.drop(at + 1)
            } ?: newest
            return older.filter { query.workflowId == null || it.workflowId == query.workflowId }
        }
    }

    private class Workflows(vararg known: RunWorkflow) : RunWorkflows {
        private val byId = known.associateBy { it.id }

        override suspend fun find(orgId: Uuid, id: String): RunWorkflow? = byId[id]?.takeIf { orgId == ORG }
    }

    private val inputs = listOf(
        WorkflowInput("branch", "Branch", required = true),
        WorkflowInput("note", "Note", default = "none", maxLength = 8),
        WorkflowInput("ticket", "Ticket"),
    )
    private val ship = RunWorkflow("ship", "Ship", 3, WorkflowStatus.EDITED, inputs)
    private val idea = RunWorkflow("idea", "Idea", 1, WorkflowStatus.PROPOSED, emptyList())
    private val runs = InMemoryStartedRuns()
    private val clock = FixedClock()
    private val start = StartWorkflowRunUseCaseImpl(Workflows(ship, idea), runs, runs, clock)
    private val list = ListWorkflowRunsUseCaseImpl(Workflows(ship, idea), runs)

    private fun person(role: OrgRole?) = GateActor("ada", ORG, ActorKind.USER, personId = "ada", role = role)

    @Test
    fun anOwnerOrAnAdminStartsAQueuedRunOfTheWorkflowsCurrentVersion() = runTest {
        val run = start(person(OrgRole.OWNER), "ship", mapOf("branch" to "main"))
        assertTrue(Regex("wfr_[0-9a-f]{32}").matches(run.id), run.id)
        assertEquals(
            WorkflowRun(
                id = run.id,
                workflowId = "ship",
                workflowName = "Ship",
                workflowVersion = 3,
                status = RunStatus.QUEUED,
                startedBy = "ada",
                source = "manual",
                inputs = mapOf("branch" to "main", "note" to "none"),
                startedAt = "2026-10-30T10:05:00.123Z",
            ),
            run,
        )
        assertEquals(listOf(Triple(ORG, run, clock.now())), runs.stored, "Stored with the clock's exact instant")
        start(person(OrgRole.ADMIN), "ship", mapOf("branch" to "main"))
        assertEquals(2, list(person(OrgRole.MEMBER), RunsQuery(workflowId = "ship")).size)
        assertEquals(2, list(person(OrgRole.MEMBER), RunsQuery()).size)
    }

    @Test
    fun anyoneElseIsRefusedBeforeTheWorkflowIsLookedUp() = runTest {
        val refused = listOf(
            person(OrgRole.MEMBER),
            person(OrgRole.APPROVER),
            person(null),
            GateActor("ci", ORG, ActorKind.SERVICE),
            GateActor("agent", ORG, ActorKind.AGENT_SESSION, launchId = "lch_1", personId = "ada"),
        )
        for (actor in refused) {
            assertFailsWith<StartRunForbiddenException> { start(actor, "absent", emptyMap()) }
        }
        assertEquals(emptyList(), runs.stored)
    }

    private fun memberStart(key: String = "automation-run:1", workflowId: String = "ship") = MemberStart(
        orgId = ORG,
        starter = "bob",
        workflowId = workflowId,
        inputs = mapOf("branch" to "main"),
        repository = "acme/api",
        key = key,
        source = "automation",
        automationId = "auto-1",
    )

    @Test
    fun anyMemberStartsARunForWhatActsInTheirNameOnceAKey() = runTest {
        val first = start.startFor(memberStart())
        assertFalse(first.replayed)
        assertEquals(
            WorkflowRun(
                id = first.run.id,
                workflowId = "ship",
                workflowName = "Ship",
                workflowVersion = 3,
                status = RunStatus.QUEUED,
                startedBy = "bob",
                source = "automation",
                automationId = "auto-1",
                inputs = mapOf("branch" to "main", "note" to "none"),
                repository = "acme/api",
                startedAt = "2026-10-30T10:05:00.123Z",
            ),
            first.run,
        )
        val details = mapOf("workflow" to "ship", "version" to "3", "source" to "automation")
        assertEquals(details, runs.audits.single().details)
        val again = start.startFor(memberStart())
        assertEquals(StartedRun(first.run, replayed = true), again)
        assertEquals(1, runs.stored.size, "A replay stores nothing")
    }

    @Test
    fun aMemberHasAtMostTwentyUnfinishedRunsAndAnUnknownOrProposedWorkflowStartsNothing() = runTest {
        repeat(MAX_UNFINISHED) { start.startFor(memberStart("k$it")) }
        val limit = assertFailsWith<RunLimitException> { start.startFor(memberStart("k20")) }
        assertEquals("run_limit", limit.type)
        assertEquals(StartedRun(runs.keys.getValue("bob" to "k0"), true), start.startFor(memberStart("k0")))
        assertFailsWith<RunWorkflowNotFoundException> { start.startFor(memberStart(workflowId = "absent")) }
        assertFailsWith<WorkflowNotAcceptedException> { start.startFor(memberStart(workflowId = "idea")) }
        assertFailsWith<InvalidRunException> { start.startFor(memberStart().copy(inputs = emptyMap())) }
    }

    @Test
    fun aKeyedStartOfACallerGivesBackItsFirstRunAndCountsTowardsTheCap() = runTest {
        val owner = person(OrgRole.OWNER)
        val first = start.startKeyed(owner, "ship", mapOf("branch" to "main"), null, "press-1")
        assertFalse(first.replayed)
        assertEquals("ada", first.run.startedBy)
        assertEquals("manual", first.run.source)
        val again = start.startKeyed(owner, "ship", mapOf("branch" to "main"), null, "press-1")
        assertEquals(StartedRun(first.run, replayed = true), again)
        assertEquals(1, runs.stored.size, "A replay stores nothing")
        assertEquals("started", runs.audits.single().event)
        // A key is the caller's own: another key starts another run, until the cap.
        repeat(MAX_UNFINISHED - 1) { start.startKeyed(owner, "ship", mapOf("branch" to "main"), null, "k$it") }
        val limit = assertFailsWith<RunLimitException> {
            start.startKeyed(owner, "ship", mapOf("branch" to "main"), null, "one-more")
        }
        assertEquals("run_limit", limit.type)
        val replay = start.startKeyed(owner, "ship", mapOf("branch" to "main"), null, "press-1")
        assertEquals(StartedRun(first.run, replayed = true), replay)
    }

    @Test
    fun aKeyedStartIsCheckedLikeAnyStartBeforeTheKeyIsLookedUp() = runTest {
        for (role in listOf(OrgRole.MEMBER, OrgRole.APPROVER, null)) {
            assertFailsWith<StartRunForbiddenException> {
                start.startKeyed(person(role), "ship", emptyMap(), null, "k")
            }
        }
        val owner = person(OrgRole.OWNER)
        assertFailsWith<RunWorkflowNotFoundException> { start.startKeyed(owner, "absent", emptyMap(), null, "k") }
        assertFailsWith<WorkflowNotAcceptedException> { start.startKeyed(owner, "idea", emptyMap(), null, "k") }
        assertFailsWith<InvalidRunException> { start.startKeyed(owner, "ship", emptyMap(), null, "k") }
        assertEquals(emptyList(), runs.stored)
    }

    @Test
    fun aRunKeepsTheProfileOfItsAgentNodesThatNameNoneGivenAtStartOrByItsAutomation() = runTest {
        val profile = Uuid.random()
        val directory = CloudProfileDirectory { orgId ->
            if (orgId == ORG) listOf(NamedCloudProfile(profile, "backend")) else emptyList()
        }
        val checked = StartWorkflowRunUseCaseImpl(Workflows(ship, idea), runs, runs, clock, directory)
        val owner = person(OrgRole.OWNER)
        val main = mapOf("branch" to "main")
        assertEquals(profile.toString(), checked(owner, "ship", main, null, profile.toString()).profileId)
        assertNull(checked(owner, "ship", main).profileId)
        val malformed = assertFailsWith<InvalidRunException> { checked(owner, "ship", main, null, "backend") }
        assertEquals("profileId: a cloud profile's id (a UUID).", malformed.message)
        val unknown = assertFailsWith<InvalidRunException> {
            checked(owner, "ship", main, null, Uuid.random().toString())
        }
        assertEquals("profileId: no cloud profile of the organization has this id.", unknown.message)
        // Where sessions run on no profile, a well-formed id is kept unchecked.
        val other = Uuid.random().toString()
        assertEquals(other, start(owner, "ship", main, null, other).profileId)
        val automated = start.startFor(memberStart().copy(profileId = profile.toString()))
        assertEquals(profile.toString(), automated.run.profileId)
        val keyed = checked.startKeyed(owner, "ship", main, null, "with-profile", profile.toString())
        assertEquals(profile.toString(), keyed.run.profileId)
    }

    @Test
    fun anUnknownOrProposedWorkflowStartsNothingAndAnUnknownOneHasNoRuns() = runTest {
        val owner = person(OrgRole.OWNER)
        assertFailsWith<RunWorkflowNotFoundException> { start(owner, "absent", emptyMap()) }
        assertFailsWith<RunWorkflowNotFoundException> { start(owner.copy(orgId = Uuid.random()), "ship", emptyMap()) }
        assertFailsWith<WorkflowNotAcceptedException> { start(owner, "idea", emptyMap()) }
        assertFailsWith<RunWorkflowNotFoundException> { list(owner, RunsQuery(workflowId = "absent")) }
        assertEquals(emptyList(), runs.stored)
    }

    @Test
    fun aPageAfterARunIsOlderAndACursorThatNamesNoRunIsRefused() = runTest {
        val owner = person(OrgRole.OWNER)
        val first = start(owner, "ship", mapOf("branch" to "main"))
        val second = start(owner, "ship", mapOf("branch" to "main"))
        assertEquals(listOf(first), list(owner, RunsQuery(before = second.id)))
        assertEquals(emptyList(), list(owner, RunsQuery(workflowId = "ship", before = first.id)), "The end: no 400")
        for (cursor in listOf("not a run", "wfr_${Uuid.random().toHexString()}")) {
            assertFailsWith<InvalidPageException>(cursor) { list(owner, RunsQuery(before = cursor)) }
        }
        val other = owner.copy(orgId = Uuid.random())
        assertFailsWith<InvalidPageException>("Another organization's run") {
            list(other, RunsQuery(before = first.id))
        }
    }

    @Test
    fun theInputsAreTheDeclaredOnesWithTheirDefaultsAndBounds() {
        assertEquals(
            mapOf("branch" to "main", "note" to "none", "ticket" to "T-1"),
            RunInputs.of(inputs, mapOf("branch" to "main", "note" to " ", "ticket" to "T-1")),
            "A blank value takes the default",
        )
        val longest = mapOf("branch" to "b", "note" to "12345678")
        assertEquals(longest, RunInputs.of(inputs, longest), "A value of exactly maxLength")
        val blankDefault = listOf(WorkflowInput("opt", "Optional", default = " "))
        assertEquals(emptyMap(), RunInputs.of(blankDefault, emptyMap()), "No value, no default: left out")
        assertEquals(emptyMap(), RunInputs.of(emptyList(), emptyMap()))
        val refused = listOf(
            mapOf("branch" to "main", "x".repeat(300) to "v") to "inputs.${"x".repeat(200)}: not an input",
            emptyMap<String, String>() to "inputs.branch: required.",
            mapOf("branch" to "\t") to "inputs.branch: required.",
            mapOf("branch" to "main", "note" to "123456789") to "inputs.note: at most 8 characters.",
        )
        for ((given, message) in refused) {
            val error = assertFailsWith<InvalidRunException> { RunInputs.of(inputs, given) }
            assertTrue(error.message.startsWith(message), error.message)
        }
    }
}
