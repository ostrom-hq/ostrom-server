// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.InferenceIssuance
import run.ostrom.server.runners.domain.RunnerInferenceTokens
import run.ostrom.server.runners.domain.RunnerTokens
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.FakeInferenceTokens
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.presentation.RunnerInferenceCredentialResponse
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.assertApiCatalogue
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The runner's inference credential against a real PostgreSQL and real HTTP (TeamCode's `CloudInferencePersistenceTest`
 * and the `inference` cases of `CloudRoutesTest`), the launches module's agent tokens by hand: issued to the runner of
 * the current execution only, two kept per execution, revoked by the write that ends it, and purged when stale.
 */
class RunnerInferencePostgresTest {
    private val launches = FakeInferenceTokens()
    private val overrides = module {
        includes(testIdentities)
        single<RunnerInferenceTokens> { launches }
    }
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private fun inferenceTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(RunnersModule), overrides) { server -> block(server) }

    private suspend fun TestServer.ask(auth: String?, body: String): HttpResponse =
        client.post("/api/v1/cloud/runner/inference") {
            auth?.let { header(HttpHeaders.Authorization, it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** A session of Alice's whose execution 2 runs, and its runner token. */
    private suspend fun TestServer.running(
        state: SessionState = SessionState.RUNNING,
        desired: DesiredState = DesiredState.RUNNING,
        agent: AgentKind = AgentKind.CLAUDE,
    ): Pair<CloudSession, String> {
        val tokens = koin.get<RunnerTokens>()
        val token = tokens.mint()
        val stored = session(state = state, desired = desired, ownerId = ALICE.toString(), createdAt = now)
            .copy(agent = agent, epoch = 2, runnerTokenHash = tokens.hash(token), executionStartedAt = now)
        assertTrue(koin.get<CloudSessionsRepository>().createWithinCap(stored, 5))
        return stored to token
    }

    private fun claim(session: CloudSession, epoch: Long = session.epoch) =
        """{"sessionId":"${session.id}","epoch":$epoch}"""

    private suspend fun TestServer.rows(): Long = database.superuser
        .fetchAll("SELECT count(*) AS n FROM runners_inference_credentials").getOrThrow().single().get("n").asLong()

    private suspend fun TestServer.becomes(session: CloudSession, change: (CloudSession) -> CloudSession) {
        val sessions = koin.get<CloudSessionsRepository>()
        val current = assertNotNull(sessions.get(session.orgId, session.id))
        assertNotNull(sessions.compareAndSet(change(current), null))
    }

    @Test
    fun theCatalogueDescribesEachRouteOnce() {
        // The members' side of the organization, so that the transfers and the preparation are mounted too.
        val everything = module {
            includes(overrides)
            single<MemberDirectory> {
                object : MemberDirectory {
                    override suspend fun isActiveMember(orgId: Uuid, memberId: String) = true
                }
            }
            single<MemberLogins> {
                object : MemberLogins {
                    override suspend fun idsOf(orgId: Uuid, logins: Collection<String>) = emptyMap<String, Uuid>()

                    override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>) = emptyMap<Uuid, String>()
                }
            }
        }
        serverTest(listOf(RunnersModule), everything) { server -> server.assertApiCatalogue(RunnersModule) }
    }

    @Test
    fun theRunnerOfTheCurrentExecutionGetsAnUncachedCredentialAndTheTwoNewestLive() = inferenceTest { server ->
        val (running, token) = server.running()
        val first = server.ask("Bearer $token", claim(running))
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        assertEquals("no-store", first.headers[HttpHeaders.CacheControl])
        val credential = first.body<RunnerInferenceCredentialResponse>()
        assertEquals("osa_1", credential.token)
        assertTrue(credential.expiresInMillis in (59.minutes.inWholeMilliseconds..1.hours.inWholeMilliseconds))
        assertTrue(Instant.parse(credential.expiresAt) > now + 59.minutes, credential.expiresAt)

        server.ask("Bearer $token", claim(running))
        server.ask("Bearer $token", claim(running))
        assertEquals(listOf("osa_2", "osa_3"), launches.live(), "The oldest goes after two renewals")
        assertEquals(2, server.rows())

        val fenced = server.ask("Bearer $token", claim(running, 1))
        assertEquals(HttpStatusCode.Conflict, fenced.status)
        assertTrue(""""type":"execution_fenced"""" in fenced.bodyAsText(), fenced.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, server.ask(null, claim(running)).status)
        assertEquals(HttpStatusCode.Unauthorized, server.ask("Bearer ostrom_runner_wrong", claim(running)).status)
        assertEquals(HttpStatusCode.Unauthorized, server.ask("Bearer alice", claim(running)).status, "A member's")
        assertEquals(HttpStatusCode.Unauthorized, server.ask("Bearer x", "{not json").status, "Body unread")
        assertEquals(HttpStatusCode.BadRequest, server.ask("Bearer $token", "{not json").status)
        assertEquals(HttpStatusCode.BadRequest, server.ask("Bearer $token", """{"sessionId":"no","epoch":2}""").status)
        val padded = """{"sessionId":"${running.id}","epoch":2,"pad":"${"x".repeat(5000)}"}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, server.ask("Bearer $token", padded).status)
        assertEquals(3, launches.issued.size, "No refused call issued anything")
    }

    @Test
    fun aShellAnExecutionWithoutItsLaunchOrOneThatNoLongerRunsGetsNone() = inferenceTest { server ->
        val (shell, shellToken) = server.running(agent = AgentKind.SHELL)
        val refused = server.ask("Bearer $shellToken", claim(shell))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("inference_unavailable" in refused.bodyAsText(), refused.bodyAsText())
        launches.open = false
        val (unlaunched, token) = server.running()
        assertTrue("inference_unavailable" in server.ask("Bearer $token", claim(unlaunched)).bodyAsText())
        launches.open = true
        val (deleting, deletingToken) = server.running(desired = DesiredState.TERMINATED)
        assertTrue("execution_fenced" in server.ask("Bearer $deletingToken", claim(deleting)).bodyAsText())
        assertEquals(0, launches.issued.size)
        assertEquals(0, server.rows())
    }

    @Test
    fun anExecutionThatEndedUnderTheLockKeepsNoCredential() = inferenceTest { server ->
        val store = assertNotNull(server.koin.get<InferenceWiring>().store)
        val (running, _) = server.running()
        // The runner's read is older than the row: another epoch, another runner token, or the session gone.
        val (paused, _) = server.running(SessionState.PAUSED, DesiredState.PAUSED)
        val stales = listOf(
            running.copy(epoch = 1),
            running.copy(runnerTokenHash = "other"),
            session(),
            paused.copy(state = SessionState.RUNNING, desiredState = DesiredState.RUNNING),
        )
        for (stale in stales) {
            assertEquals(InferenceIssuance.Fenced, store.issue(stale, 1.hours, 2))
        }
        assertEquals(launches.issued.map { it.id }, launches.revoked, "Each minted token was revoked at once")
        assertEquals(0, server.rows())
    }

    @Test
    fun theWriteThatEndsAnExecutionRevokesItsCredentials() = inferenceTest { server ->
        suspend fun issue(session: CloudSession, token: String) =
            assertEquals(HttpStatusCode.OK, server.ask("Bearer $token", claim(session)).status)

        // A pause: the draining execution keeps its inference, and loses it when its pod stops.
        val (paused, pausedToken) = server.running()
        issue(paused, pausedToken)
        server.becomes(paused) { it.copy(state = SessionState.PAUSING) }
        issue(paused, pausedToken)
        assertEquals(listOf("osa_1", "osa_2"), launches.live())
        server.becomes(paused) { it.copy(state = SessionState.PAUSED, runnerTokenHash = null) }
        assertEquals(emptyList(), launches.live())

        // A later epoch revokes the earlier one's, and deletion revokes them all.
        val (later, laterToken) = server.running()
        issue(later, laterToken)
        server.becomes(later) { it.copy(epoch = 3) }
        assertEquals(emptyList(), launches.live(), "A later execution")
        val (deleted, deletedToken) = server.running()
        issue(deleted, deletedToken)
        server.koin.get<CloudSessionsRepository>()
            .setDesiredState(deleted.orgId, deleted.id, DesiredState.TERMINATED, now)
        assertEquals(emptyList(), launches.live(), "Deletion, before the pod is gone")
        assertEquals(0, server.rows())
    }

    @Test
    fun aRevocationThatFailsIsLeftToThePurgeAndACancellationStopsTheWrite() = inferenceTest { server ->
        val store = assertNotNull(server.koin.get<InferenceWiring>().store)
        val (lost, token) = server.running()
        assertEquals(HttpStatusCode.OK, server.ask("Bearer $token", claim(lost)).status)
        launches.failure = IllegalStateException("identity is down")
        server.becomes(lost) { it.copy(state = SessionState.PAUSED, runnerTokenHash = null) }
        assertEquals(listOf("osa_1"), launches.live(), "The write stands; the token is left")
        assertEquals(1, server.rows(), "Its row too, for the purge")

        launches.failure = CancellationException("stopping")
        assertFailsWith<CancellationException> { store.revokeEnded(lost.copy(state = SessionState.PAUSED)) }
        launches.failure = null
        assertEquals(1, store.purge(100))
        assertEquals(emptyList(), launches.live())
        assertEquals(0, server.rows())
    }

    @Test
    fun thePurgeRevokesExpiredCredentialsAndThoseOfExecutionsThatNoLongerRun() = inferenceTest { server ->
        val store = assertNotNull(server.koin.get<InferenceWiring>().store)
        val (expiring, expiringToken) = server.running()
        val (orphaned, orphanedToken) = server.running()
        val (live, liveToken) = server.running()
        for ((session, token) in listOf(expiring to expiringToken, orphaned to orphanedToken, live to liveToken)) {
            assertEquals(HttpStatusCode.OK, server.ask("Bearer $token", claim(session)).status)
        }
        val fixtures = server.database.superuser
        fixtures.execute(
            "UPDATE runners_inference_credentials SET expires_at = now() - INTERVAL '1 second' " +
                "WHERE session_id = '${expiring.id}'",
        ).getOrThrow()
        // Moved on without the repository (a crash between the write and its revocation).
        fixtures.execute("UPDATE runners_sessions SET epoch = 3 WHERE id = '${orphaned.id}'").getOrThrow()

        assertEquals(2, store.purge(100))
        assertEquals(listOf("osa_3"), launches.live())
        assertEquals(1, server.rows())
        assertEquals(0, store.purge(100), "Nothing left to purge")
        assertIs<InferenceIssuance.Issued>(store.issue(live, 1.hours, 2))
    }

    @Test
    fun theModulePurgesStaleCredentialsOnItsOwn() = inferenceTest { server ->
        val (lost, token) = server.running()
        assertEquals(HttpStatusCode.OK, server.ask("Bearer $token", claim(lost)).status)
        server.database.superuser.execute("UPDATE runners_sessions SET epoch = 3 WHERE id = '${lost.id}'").getOrThrow()
        val job = Job()
        RunnersModule.start(CoroutineScope(currentCoroutineContext() + job), server.koin)
        withTimeout(20.seconds) {
            while (server.rows() > 0) delay(100.milliseconds)
        }
        job.cancelAndJoin()
        assertEquals(emptyList(), launches.live())
    }

    @Test
    fun aFailedRevocationOfTheOlderCredentialStillHandsTheNewOneAndThePurgeCarriesOn() = inferenceTest { server ->
        val store = assertNotNull(server.koin.get<InferenceWiring>().store)
        val (running, token) = server.running()
        repeat(2) { assertEquals(HttpStatusCode.OK, server.ask("Bearer $token", claim(running)).status) }
        launches.failure = IllegalStateException("identity is down")
        val renewed = server.ask("Bearer $token", claim(running))
        assertEquals(HttpStatusCode.OK, renewed.status, "The older one expires on its own")
        assertEquals("osa_3", renewed.body<RunnerInferenceCredentialResponse>().token)
        assertEquals(2, server.rows())
        launches.failure = CancellationException("stopping")
        assertFailsWith<CancellationException> { store.issue(running, 1.hours, 2) }

        // The purge skips an organization it cannot revoke in, and counts only what it deleted.
        launches.failure = IllegalStateException("identity is down")
        server.becomes(running) { it.copy(state = SessionState.PAUSED, runnerTokenHash = null) }
        assertEquals(0, store.purge(100))
        launches.failure = CancellationException("stopping")
        assertFailsWith<CancellationException> { store.purge(100) }
        launches.failure = null
        assertEquals(2, store.purge(100))
        assertEquals(0, server.rows())
    }
}
