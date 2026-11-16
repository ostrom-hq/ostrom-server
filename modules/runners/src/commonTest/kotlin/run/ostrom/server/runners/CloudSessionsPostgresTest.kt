// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.events.SessionNames
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.sessions.CloudSessionWakes
import run.ostrom.server.kernel.sessions.PendingWaitAnswers
import run.ostrom.server.cloud.domain.CloudCluster
import run.ostrom.server.cloud.domain.CloudClustersRepository
import run.ostrom.server.cloud.domain.StoredCloudCluster
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.ScheduledWork
import run.ostrom.server.runners.domain.AllowlistedCloudPlanProvider
import run.ostrom.server.runners.domain.RuntimeDriver
import run.ostrom.server.runners.domain.SessionKind
import run.ostrom.server.runners.fakes.FakeClusterRuntime
import run.ostrom.server.runners.domain.CloudPlan
import run.ostrom.server.runners.domain.CloudPlanProvider
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionRetainedLimitException
import run.ostrom.server.runners.domain.CreateKey
import run.ostrom.server.runners.domain.ReconcileCloudSessionUseCase
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.ResumeOutcome
import run.ostrom.server.runners.domain.RunnerTokens
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.infrastructure.SettingsAllowedOrgs
import run.ostrom.server.runners.infrastructure.RunnersSessionsTable
import run.ostrom.server.runners.infrastructure.RunnersUsage
import run.ostrom.server.runners.presentation.CloudSessionResponse
import run.ostrom.server.runners.presentation.CloudSessionsResponse
import run.ostrom.server.runners.presentation.CreateCloudSessionRequest
import run.ostrom.server.runners.presentation.RunnerHeartbeatRequest
import run.ostrom.server.runners.presentation.RunnerHeartbeatResponse
import run.ostrom.server.runners.presentation.RunnersPage
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Against a real PostgreSQL: the repository's SQL and the routes, with a fake identity. */
class CloudSessionsPostgresTest {
    private val identities = testIdentities

    /** No plan caps the agents at once since 14/11/2026: a cap of five, to see the route answer 402 past it. */
    private val cappedAtFive = module {
        includes(identities)
        single<CloudPlanProvider> {
            val capped = object : CloudPlanProvider {
                override suspend fun planFor(orgId: Uuid): CloudPlan = CloudPlan.FREE.copy(maxRunningSessions = 5)
            }
            AllowlistedCloudPlanProvider(SettingsAllowedOrgs(get()), capped)
        }
    }

    private val t0 = Instant.parse("2026-10-30T10:00:00Z")

    @Test
    fun theNotificationRouterReadsTheNamesOfAnOrganizationsCloudSessionsInOneStatement() =
        serverTest(listOf(RunnersModule)) { server ->
            val repository = server.koin.get<CloudSessionsRepository>()
            val login = session(createdAt = t0).copy(name = "Fix the login page")
            val other = session(createdAt = t0).copy(name = "Upgrade Gradle")
            val elsewhere = session(orgId = ORG_B, createdAt = t0)
            for (created in listOf(login, other, elsewhere)) assertTrue(repository.createWithinCap(created, 5))
            val names = server.koin.get<SessionNames>()

            assertEquals(
                mapOf(login.id.toString() to "Fix the login page", other.id.toString() to "Upgrade Gradle"),
                names.of(ORG_A, setOf(login.id.toString(), other.id.toString(), elsewhere.id.toString(), "s-local")),
                "Another organization's session and an agent session's id name none",
            )
            assertEquals(emptyMap(), names.of(ORG_A, setOf("s-local")))
        }

    @Test
    fun repositoryStoresReadsAndComparesAndSets() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val created = session(createdAt = t0).copy(
            repository = run.ostrom.server.runners.domain.RepositorySpec("https://github.com/acme/api", "main"),
            failure = SessionFailure("idle_timeout", "Paused."),
            automationId = Uuid.random(),
        )
        assertTrue(repository.createWithinCap(created, 5))

        assertEquals(created, repository.get(ORG_A, created.id))
        assertEquals(created, repository.getById(created.id))
        assertNull(repository.get(ORG_B, created.id))

        val next = created.copy(
            state = SessionState.RUNNING,
            epoch = 1,
            runnerTokenHash = "h1",
            readyAt = t0,
            lastActivityAt = t0 + 5.seconds,
            backgroundSince = t0 + 1.seconds,
            backgroundSeenAt = t0 + 2.seconds,
        )
        val stored = assertNotNull(repository.compareAndSet(next, t0))
        assertEquals(1, stored.generation)
        assertEquals(t0 + 1.seconds to t0 + 2.seconds, stored.backgroundSince to stored.backgroundSeenAt)
        assertEquals(stored, repository.findByRunnerTokenHash("h1"))
        assertNull(repository.findByRunnerTokenHash("h2"))
        // A stale generation loses.
        assertNull(repository.compareAndSet(next, t0))
        // The activity never goes back.
        val back = assertNotNull(repository.compareAndSet(stored.copy(lastActivityAt = t0), null))
        assertEquals(t0 + 5.seconds, back.lastActivityAt)
        repository.touchActivity(created.id, t0 + 1.seconds)
        assertEquals(t0 + 5.seconds, repository.getById(created.id)?.lastActivityAt)
        repository.touchActivity(created.id, t0 + 9.seconds)
        assertEquals(t0 + 9.seconds, repository.getById(created.id)?.lastActivityAt)
        repository.touchActivity(Uuid.random(), t0)
        assertNull(repository.getById(Uuid.random()))
    }

    @Test
    fun anotherOrganizationsRowsAreNeitherSeenNorWritten() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val ofA = session(createdAt = t0)
        repository.createWithinCap(ofA, 5)
        repository.addUsage(ORG_A, "2026-10", 60)
        val jdbc = server.koin.get<JdbcDatabase>()
        val sessions = RunnersSessionsTable
        val usage = RunnersUsage
        assertEquals(1L, jdbc.inOrg(ORG_A) { sessions.selectAll().count() })
        assertEquals(1L, jdbc.inOrg(ORG_A) { usage.selectAll().count() })
        // No WHERE org_id: the policy alone hides organization A's rows.
        assertEquals(0L, jdbc.inOrg(ORG_B) { sessions.selectAll().count() })
        assertEquals(0L, jdbc.inOrg(ORG_B) { usage.selectAll().count() })
        // A write for another organization is refused.
        assertFailsWith<Exception> {
            jdbc.inOrg(ORG_B) {
                usage.insert {
                    it[orgId] = ORG_A
                    it[month] = "2026-10"
                    it[seconds] = 1L
                }
            }
        }
        assertEquals(60, repository.usageSeconds(ORG_A, "2026-10"))
        // Without an organization set, the serving role sees nothing.
        assertEquals(0L, jdbc.query { sessions.selectAll().count() })
    }

    @Test
    fun theRunningCapHoldsUnderConcurrentCreations() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val results = coroutineScope {
            (1..12).map { async { repository.createWithinCap(session(createdAt = t0), 5) } }.awaitAll()
        }
        assertEquals(5, results.count { it })
    }

    @Test
    fun theRetainedCapCountsTheOwnersKeptCloudSessionsAndNoneLiftsIt() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        // A paused session counts; a failed one, another member's and another organization's do not.
        assertTrue(repository.createWithinCap(paused(), 100, null, 2))
        assertTrue(repository.createWithinCap(session(SessionState.FAILED, DesiredState.PAUSED, createdAt = t0), 100))
        assertTrue(repository.createWithinCap(session(ownerId = "bob", createdAt = t0), 100))
        assertTrue(repository.createWithinCap(session(orgId = ORG_B, createdAt = t0), 100))
        assertTrue(repository.createWithinCap(session(createdAt = t0), 100, null, 2))
        val over = assertFailsWith<CloudSessionRetainedLimitException> {
            repository.createWithinCap(session(createdAt = t0), 100, null, 2)
        }
        assertEquals("cloud_session_limit", over.type)
        assertFailsWith<CloudSessionRetainedLimitException> {
            repository.createKeyed(session(createdAt = t0), 100, CreateKey("k", "d", t0 + 60.seconds), null, 2)
        }
        // An automation's session is outside the cap, and not counted.
        val run = session(createdAt = t0).copy(automationId = Uuid.random())
        assertTrue(repository.createWithinCap(run, 100, null, 2))
        // 0 removes the retained cap (Int.MAX_VALUE): the same member keeps creating sessions.
        assertTrue(repository.createWithinCap(session(createdAt = t0), 100, null, Int.MAX_VALUE))
    }

    private fun paused() = session(SessionState.PAUSED, DesiredState.PAUSED, createdAt = t0)

    @Test
    fun resumeWithinCapCountsOnlyOtherHoldingSessions() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val paused = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED, createdAt = t0)
        repository.createWithinCap(paused, 5)
        repository.createWithinCap(session(createdAt = t0), 5)

        assertEquals(ResumeOutcome.AtCap, repository.resumeWithinCap(ORG_A, paused.id, 1, t0))
        val resumed = repository.resumeWithinCap(ORG_A, paused.id, 2, t0)
        assertEquals(DesiredState.RUNNING, (resumed as ResumeOutcome.Resumed).session.desiredState)
        assertEquals(ResumeOutcome.NotFound, repository.resumeWithinCap(ORG_B, paused.id, 2, t0))
        assertNull(repository.setDesiredState(ORG_B, paused.id, DesiredState.PAUSED, t0))
    }

    @Test
    fun resumeWithinCapLeavesADeletedOrEndedSessionAsItIs() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val deleting = session(state = SessionState.PAUSED, desired = DesiredState.TERMINATED, createdAt = t0)
        val failed = session(state = SessionState.FAILED, desired = DesiredState.PAUSED, createdAt = t0)
        repository.createWithinCap(deleting, 5)
        repository.createWithinCap(failed, 5)

        assertEquals(ResumeOutcome.NotFound, repository.resumeWithinCap(ORG_A, deleting.id, 5, t0))
        assertEquals(ResumeOutcome.NotFound, repository.resumeWithinCap(ORG_A, failed.id, 5, t0))
        assertEquals(DesiredState.TERMINATED, repository.get(ORG_A, deleting.id)?.desiredState)
        assertEquals(DesiredState.PAUSED, repository.get(ORG_A, failed.id)?.desiredState)
    }

    @Test
    fun anAnswerToAWaitResumesAPausedSessionOnItsOwnersBehalf() {
        val audit = mutableListOf<OrgAuditRecord>()
        val overrides = module {
            includes(testIdentities)
            single<MemberDirectory> {
                object : MemberDirectory {
                    override suspend fun isActiveMember(orgId: Uuid, memberId: String) = memberId == "alice"
                }
            }
            single<OrgAuditTrail> { OrgAuditTrail { audit += it } }
            // The idle guard's answers, as automations binds them.
            single<PendingWaitAnswers> { PendingWaitAnswers { _, _, _ -> false } }
        }
        serverTest(listOf(RunnersModule), overrides) { server ->
            assertNotNull(server.koin.get<ReconcileCloudSessionUseCase>())
            val repository = server.koin.get<CloudSessionsRepository>()
            val wakes = server.koin.get<CloudSessionWakes>()
            val paused = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED, createdAt = t0)
            repository.createWithinCap(paused, 5)
            val waitId = Uuid.random()

            assertTrue(wakes.wake(ORG_A, paused.id.toString(), waitId))
            assertEquals(DesiredState.RUNNING, repository.get(ORG_A, paused.id)?.desiredState)
            assertFalse(wakes.wake(ORG_A, paused.id.toString(), waitId), "Once")
            assertFalse(wakes.wake(ORG_B, paused.id.toString(), waitId), "Another organization's")
            assertFalse(wakes.wake(ORG_A, "s-local", waitId), "A local launch's session")
            assertEquals(listOf("cloud.session.woken"), audit.map { it.event })
        }
    }

    @Test
    fun finalSessionsKeepTheirDesiredState() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val over = session(state = SessionState.TERMINATED, desired = DesiredState.TERMINATED, createdAt = t0)
        repository.createWithinCap(over, 5)
        assertEquals(
            DesiredState.TERMINATED,
            repository.setDesiredState(ORG_A, over.id, DesiredState.RUNNING, t0)?.desiredState,
        )
    }

    @Test
    fun claimsAreLeasedAndReleasesKeepRequestsMadeDuringThePass() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val due = session(createdAt = t0)
        repository.createWithinCap(due, 5)

        assertEquals(listOf(due.id), repository.claimDue("a", t0, t0 + 60.seconds, 8).map { it.id })
        // Leased: another replica gets nothing.
        assertEquals(emptyList(), repository.claimDue("b", t0 + 1.seconds, t0 + 61.seconds, 8))
        // An API write during the pass asks for a new look; the end of the pass keeps it.
        repository.setDesiredState(ORG_A, due.id, DesiredState.PAUSED, t0 + 2.seconds)
        repository.release(due.id, "a", null)
        assertEquals(listOf(due.id), repository.claimDue("b", t0 + 3.seconds, t0 + 63.seconds, 8).map { it.id })
        // Released by its owner only; without a request, null means never.
        repository.release(due.id, "a", t0)
        repository.release(due.id, "b", null)
        assertEquals(emptyList(), repository.claimDue("c", t0 + 1000.seconds, t0 + 1060.seconds, 8))
        // A next time is kept.
        repository.setDesiredState(ORG_A, due.id, DesiredState.RUNNING, t0 + 4.seconds)
        repository.claimDue("c", t0 + 5.seconds, t0 + 65.seconds, 8)
        repository.release(due.id, "c", t0 + 20.seconds)
        assertEquals(emptyList(), repository.claimDue("d", t0 + 19.seconds, t0 + 79.seconds, 8))
        assertEquals(listOf(due.id), repository.claimDue("d", t0 + 20.seconds, t0 + 80.seconds, 8).map { it.id })
        // A request during the pass and a next time: the earliest wins.
        repository.setDesiredState(ORG_A, due.id, DesiredState.PAUSED, t0 + 21.seconds)
        repository.release(due.id, "d", t0 + 30.seconds)
        assertEquals(listOf(due.id), repository.claimDue("e", t0 + 21.seconds, t0 + 81.seconds, 8).map { it.id })
    }

    @Test
    fun theModuleStartsTheReconcilerLoop() = serverTest(listOf(RunnersModule)) { server ->
        val job = Job()
        RunnersModule.start(CoroutineScope(currentCoroutineContext() + job), server.koin)
        assertEquals(4, job.children.count(), "Its loops and the create keys purge, no garbage collection")
        job.cancelAndJoin()
    }

    @Test
    fun onTheConsolesClustersTheModuleStartsTheGarbageCollectionToo() = serverTest(
        listOf(RunnersModule),
        module {
            single<RuntimeDriver> { FakeClusterRuntime() }
            single<ScheduledWork> {
                object : ScheduledWork {
                    override suspend fun runDue(
                        name: String,
                        interval: Duration,
                        timeout: Duration,
                        block: suspend () -> Unit,
                    ) = false
                }
            }
            single<CloudClustersRepository> {
                object : CloudClustersRepository {
                    override suspend fun list() = emptyList<CloudCluster>()
                    override suspend fun get(id: Uuid): StoredCloudCluster? = null
                    override suspend fun version(id: Uuid): Int? = null
                    override suspend fun insert(stored: StoredCloudCluster) = false
                    override suspend fun update(stored: StoredCloudCluster) = false
                    override suspend fun recordGarbageCollection(id: Uuid, orphanedVolumes: Int?, at: Instant) = Unit
                }
            }
        },
    ) { server ->
        val job = Job()
        RunnersModule.start(CoroutineScope(currentCoroutineContext() + job), server.koin)
        assertEquals(5, job.children.count(), "And the garbage collection")
        job.cancelAndJoin()
    }

    @Test
    fun usageAddsUpPerOrganizationAndMonth() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        assertEquals(0, repository.usageSeconds(ORG_A, "2026-10"))
        repository.addUsage(ORG_A, "2026-10", 15)
        repository.addUsage(ORG_A, "2026-10", 30)
        repository.addUsage(ORG_A, "2026-11", 7)
        assertEquals(45, repository.usageSeconds(ORG_A, "2026-10"))
        assertEquals(7, repository.usageSeconds(ORG_A, "2026-11"))
        assertEquals(0, repository.usageSeconds(ORG_B, "2026-10"))
    }

    @Test
    fun listPagesByKeyset() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val sessions = (0 until 3).map { index ->
            session(createdAt = t0 + index.seconds).also { repository.createWithinCap(it, 10) }
        }
        repository.createWithinCap(session(ownerId = "bob", createdAt = t0), 10)
        val running = session(createdAt = t0 + 5.seconds).copy(state = SessionState.RUNNING)
        repository.createWithinCap(running, 10)

        assertEquals(
            listOf(running.id) + sessions.reversed().map { it.id },
            repository.list(ORG_A, "alice", null, 10).map { it.id },
        )
        // TeamCode's `state` filter: only the states named.
        val cloud = setOf(SessionKind.CLOUD)
        val runningOnly = repository.list(ORG_A, "alice", null, 10, cloud, setOf(SessionState.RUNNING))
        assertEquals(listOf(running.id), runningOnly.map { it.id })
        val both = setOf(SessionState.RUNNING, SessionState.PAUSED)
        assertEquals(listOf(running.id), repository.list(ORG_A, "alice", null, 10, cloud, both).map { it.id })
        assertEquals(listOf(sessions[0].id), repository.list(ORG_A, "alice", sessions[1].id, 10).map { it.id })
        assertEquals(emptyList(), repository.list(ORG_B, "alice", null, 10))
    }

    @Test
    fun theListFiltersByStatusAsTeamCodesStateAndNamesNoAutomationOfAMembersSession() =
        serverTest(listOf(RunnersModule), identities) { server ->
            val session = server.client.post("/api/v1/cloud/sessions") {
                bearerAuth("alice")
                contentType(ContentType.Application.Json)
                setBody(CreateCloudSessionRequest(agent = "codex", name = "Filtered"))
            }.body<CloudSessionResponse>()
            assertNull(session.automationId, "A member's own session names no automation")
            val creating =
                server.client.get("/api/v1/cloud/sessions?status=creating&status=running") { bearerAuth("alice") }
            assertEquals(listOf(session.id), creating.body<CloudSessionsResponse>().items.map { it.id })
            val noneYet = server.client.get("/api/v1/cloud/sessions?status=paused") { bearerAuth("alice") }
            assertEquals(emptyList(), noneYet.body<CloudSessionsResponse>().items.map { it.id })
            val unknown = server.client.get("/api/v1/cloud/sessions?status=active") { bearerAuth("alice") }
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
            assertTrue("invalid_state" in unknown.bodyAsText())
        }

    @Test
    fun routesCreateListReadPauseResumeAndDelete() = serverTest(listOf(RunnersModule), identities) { server ->
        val created = server.client.post("/api/v1/cloud/sessions") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(
                CreateCloudSessionRequest(
                    agent = "codex",
                    name = "Fix it",
                    repo = "https://github.com/acme/api",
                    ref = "main",
                    prompt = "Fix the tests",
                    model = "gpt-5.5-codex",
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val session = created.body<CloudSessionResponse>()
        assertEquals("creating", session.status)
        assertEquals(ALICE.toString(), session.owner)
        assertEquals("gpt-5.5-codex", session.model)
        assertEquals("codex", session.agent)
        assertEquals("https://github.com/acme/api", session.repo)
        assertEquals("main", session.ref)
        assertEquals(30, session.limits.idleTimeoutMinutes)

        val list = server.client.get(
            "/api/v1/cloud/sessions?limit=1",
        ) { bearerAuth("alice") }.body<CloudSessionsResponse>()
        assertEquals(listOf(session.id), list.items.map { it.id })
        assertEquals(session.id, list.next)
        val partial = server.client.get("/api/v1/cloud/sessions") { bearerAuth("alice") }.body<CloudSessionsResponse>()
        assertEquals(listOf(session.id), partial.items.map { it.id })
        assertNull(partial.next)
        val empty = server.client.get(
            "/api/v1/cloud/sessions?before=${session.id}",
        ) { bearerAuth("alice") }.body<CloudSessionsResponse>()
        assertEquals(emptyList(), empty.items)
        assertNull(empty.next)
        assertEquals(
            session.id,
            server.client.get(
                "/api/v1/cloud/sessions/${session.id}",
            ) { bearerAuth("reader") }.body<CloudSessionResponse>().id,
        )

        val paused = server.client.post("/api/v1/cloud/sessions/${session.id}/pause") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.Accepted, paused.status)
        assertEquals("paused", paused.body<CloudSessionResponse>().desiredStatus)
        val resumed = server.client.post("/api/v1/cloud/sessions/${session.id}/resume") { bearerAuth("alice") }
        assertEquals("running", resumed.body<CloudSessionResponse>().desiredStatus)
        val deleted = server.client.delete("/api/v1/cloud/sessions/${session.id}") { bearerAuth("alice") }
        assertEquals(HttpStatusCode.Accepted, deleted.status)
        assertEquals("terminated", deleted.body<CloudSessionResponse>().desiredStatus)
    }

    @Test
    fun executionsAreTheOwnersSessionsHoldingOne() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val holding = SessionState.entries.filter { it.holdsExecution }
        val sessions = SessionState.entries.mapIndexed { index, state ->
            session(state, createdAt = t0 + index.seconds).also { repository.createWithinCap(it, 100) }
        }
        repository.createWithinCap(session(SessionState.RUNNING, ownerId = "bob", createdAt = t0), 100)

        val expected = sessions.filter { it.state in holding }.reversed().map { it.id }
        assertEquals(expected, repository.executions(ORG_A, "alice", 10).map { it.id })
        assertEquals(expected.take(1), repository.executions(ORG_A, "alice", 1).map { it.id })
        assertEquals(emptyList(), repository.executions(ORG_B, "alice", 10))
    }

    @Test
    fun runnersRouteListsTheCallersRunnersOnly() = serverTest(listOf(RunnersModule), identities) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val now = kotlin.time.Clock.System.now()
        val mine = session(SessionState.RUNNING, ownerId = ALICE.toString(), createdAt = now)
            .copy(epoch = 1, lastHeartbeatAt = now)
        repository.createWithinCap(mine, 10)
        repository.createWithinCap(session(SessionState.CREATING, ownerId = ALICE.toString(), orgId = ORG_B), 10)

        for (token in listOf("alice", "reader")) {
            val page = server.client.get("/api/v1/runners") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, page.status)
            val runner = page.body<RunnersPage>().items.single()
            assertEquals(mine.id.toString(), runner.id)
            assertEquals("online", runner.status)
            assertEquals(listOf("claude"), runner.labels)
            assertEquals("latest", runner.version)
        }
        // Another member of the organization and another organization see none of alice's runners.
        for (stranger in listOf("bob", "carol")) {
            val page = server.client.get("/api/v1/runners") { bearerAuth(stranger) }.body<RunnersPage>()
            assertEquals(emptyList(), page.items, stranger)
        }
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/runners").status)
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.get("/api/v1/runners") { bearerAuth("nobody") }.status,
        )
    }

    @Test
    fun routesRefuseWithoutTokenOrganizationOrScope() = serverTest(listOf(RunnersModule), identities) { server ->
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/cloud/sessions").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            server.client.get("/api/v1/cloud/sessions") { bearerAuth("unknown") }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.get("/api/v1/cloud/sessions") { bearerAuth("nobody") }.status,
        )
        val readOnly = server.client.post("/api/v1/cloud/sessions") {
            bearerAuth("reader")
            contentType(ContentType.Application.Json)
            setBody(CreateCloudSessionRequest(agent = "claude", name = "x"))
        }
        assertEquals(HttpStatusCode.Forbidden, readOnly.status)
    }

    @Test
    fun anotherOrganizationOrMemberSeesNothing() = serverTest(listOf(RunnersModule), identities) { server ->
        val session = server.client.post("/api/v1/cloud/sessions") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(CreateCloudSessionRequest(agent = "claude", name = "Mine"))
        }.body<CloudSessionResponse>()

        for (stranger in listOf("carol", "bob")) {
            assertEquals(
                HttpStatusCode.NotFound,
                server.client.get("/api/v1/cloud/sessions/${session.id}") { bearerAuth(stranger) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                server.client.post("/api/v1/cloud/sessions/${session.id}/pause") { bearerAuth(stranger) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                server.client.post("/api/v1/cloud/sessions/${session.id}/resume") { bearerAuth(stranger) }.status,
            )
            assertEquals(
                HttpStatusCode.NotFound,
                server.client.delete("/api/v1/cloud/sessions/${session.id}") { bearerAuth(stranger) }.status,
            )
            val list = server.client.get(
                "/api/v1/cloud/sessions",
            ) { bearerAuth(stranger) }.body<CloudSessionsResponse>()
            assertEquals(emptyList(), list.items)
        }
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.get("/api/v1/cloud/sessions/not-a-uuid") { bearerAuth("alice") }.status,
        )
    }

    @Test
    fun routesMapRefusalsToStatuses() = serverTest(listOf(RunnersModule), cappedAtFive) { server ->
        val bad = server.client.post("/api/v1/cloud/sessions") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(CreateCloudSessionRequest(agent = "cursor", name = "x"))
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        repeat(5) {
            server.client.post("/api/v1/cloud/sessions") {
                bearerAuth("alice")
                contentType(ContentType.Application.Json)
                setBody(CreateCloudSessionRequest(agent = "claude", name = "s$it"))
            }
        }
        val capped = server.client.post("/api/v1/cloud/sessions") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody(CreateCloudSessionRequest(agent = "claude", name = "one too many"))
        }
        assertEquals(HttpStatusCode.PaymentRequired, capped.status)
        val closed = server.client.post("/api/v1/cloud/sessions") {
            bearerAuth("carol")
            contentType(ContentType.Application.Json)
            setBody(CreateCloudSessionRequest(agent = "claude", name = "not allowed here"))
        }
        assertEquals(HttpStatusCode.Forbidden, closed.status)
        assertEquals(
            HttpStatusCode.BadRequest,
            server.client.get("/api/v1/cloud/sessions?before=nope") { bearerAuth("alice") }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            server.client.get("/api/v1/cloud/sessions?limit=99") { bearerAuth("alice") }.status,
        )
    }

    @Test
    fun theRunnerChannelAuthenticatesByRunnerTokenAndFencesOldEpochs() = serverTest(
        listOf(RunnersModule),
        identities,
    ) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val tokens = server.koin.get<RunnerTokens>()
        val token = tokens.mint()
        val starting: CloudSession = session(
            createdAt = t0,
        ).copy(epoch = 2, runnerTokenHash = tokens.hash(token), executionStartedAt = t0)
        repository.createWithinCap(starting, 5)

        suspend fun beat(
            body: RunnerHeartbeatRequest,
            auth: String? = token,
        ) = server.client.post("/api/v1/cloud/runner/heartbeat") {
            if (auth != null) header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        val ready = beat(
            RunnerHeartbeatRequest(
                starting.id.toString(),
                2,
                ready = true,
                lastModelRequestAt = "2026-10-30T10:00:00Z",
            ),
            "Bearer $token",
        )
        assertEquals(HttpStatusCode.OK, ready.status)
        assertEquals(RunnerHeartbeatResponse("run", "running"), ready.body<RunnerHeartbeatResponse>())
        assertEquals(
            HttpStatusCode.Conflict,
            beat(RunnerHeartbeatRequest(starting.id.toString(), 1), "Bearer $token").status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            beat(RunnerHeartbeatRequest(starting.id.toString(), 2), "Bearer ostrom_runner_wrong").status,
        )
        assertEquals(HttpStatusCode.Unauthorized, beat(RunnerHeartbeatRequest(starting.id.toString(), 2), null).status)
        assertEquals(HttpStatusCode.Unauthorized, beat(RunnerHeartbeatRequest(starting.id.toString(), 2), token).status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            beat(RunnerHeartbeatRequest(starting.id.toString(), 2), "Bearer ").status,
        )
        // A member's token is not a runner token.
        assertEquals(
            HttpStatusCode.Unauthorized,
            beat(RunnerHeartbeatRequest(starting.id.toString(), 2), "Bearer alice").status,
        )
        assertEquals(HttpStatusCode.BadRequest, beat(RunnerHeartbeatRequest("nope", 2), "Bearer $token").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            beat(
                RunnerHeartbeatRequest(starting.id.toString(), 2, lastModelRequestAt = "yesterday"),
                "Bearer $token",
            ).status,
        )

        // S2-46: the token is checked before the body is read, and the body is capped.
        suspend fun raw(text: String, auth: String) = server.client.post("/api/v1/cloud/runner/heartbeat") {
            header(HttpHeaders.Authorization, auth)
            contentType(ContentType.Application.Json)
            setBody(text)
        }
        assertEquals(HttpStatusCode.Unauthorized, raw("{not json", "Bearer x").status, "Unknown token: body unread")
        assertEquals(HttpStatusCode.BadRequest, raw("{not json", "Bearer $token").status)
        val padded = """{"sessionId":"${starting.id}","epoch":2,"pad":"${"x".repeat(5000)}"}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, raw(padded, "Bearer $token").status)
    }

    @Test
    fun aRandomIdIsNotFound() = serverTest(listOf(RunnersModule), identities) { server ->
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.get("/api/v1/cloud/sessions/${Uuid.random()}") { bearerAuth("alice") }.status,
        )
    }
}
