// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.SignedInDevices
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.LocalLaunches
import run.ostrom.server.runners.domain.RemoteSessionRules
import run.ostrom.server.runners.domain.SessionKind
import run.ostrom.server.runners.domain.SessionMode
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.FakeSignedInDevices
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_A_ALLOWED
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.REMOTE_LOGINS
import run.ostrom.server.runners.fakes.TestOrgResolver
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.presentation.CloudHostResponse
import run.ostrom.server.runners.presentation.CloudSessionResponse
import run.ostrom.server.runners.presentation.CloudSessionsResponse
import run.ostrom.server.runners.presentation.CreateCloudSessionRequest
import run.ostrom.server.runners.presentation.RunnerHeartbeatRequest
import run.ostrom.server.runners.presentation.RunnerHeartbeatResponse
import run.ostrom.server.testing.TestInstanceSettings
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Local sessions against a real PostgreSQL (TeamCode's `LocalSessionPersistenceTest`): opened once per launch (the
 * unique index), only for the caller's own running launch, listed as their own kind, hosted from any device of their
 * owner's, refused a pause, a resume, a message and a GitHub access change, and deleted when the agent exits.
 */
class LocalSessionsPostgresTest {
    private val running = mutableSetOf(LAUNCH, SECOND_LAUNCH)

    /** The test identities with the CLI sign-ins of [REMOTE_LOGINS], their devices, and Alice's running launches. */
    private val local = module {
        single<PrincipalOrgResolver> { TestOrgResolver }
        single<ConfigSource> { MapConfigSource(emptyMap()) }
        single<InstanceSettings> { TestInstanceSettings(ORG_A_ALLOWED) }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? =
                    REMOTE_LOGINS[token] ?: BASE.authenticate(token)
            }
        }
        single<SignedInDevices> { FakeSignedInDevices() }
        single<LocalLaunches> {
            LocalLaunches { orgId, userId, launchId -> orgId == ORG_A && userId == "alice" && launchId in running }
        }
    }

    private val t0 = Instant.parse("2026-10-30T10:00:00Z")

    private val draft = CreateCloudSessionRequest(agent = "claude", kind = "local", launchId = LAUNCH)

    private suspend fun HttpClient.create(token: String, request: CreateCloudSessionRequest = draft) =
        post("/api/v1/cloud/sessions") {
            bearerAuth(token)
            header("Idempotency-Key", request.launchId ?: "none")
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.host(token: String, id: String) =
        post("/api/v1/cloud/sessions/$id/host") { bearerAuth(token) }

    private suspend fun HttpClient.beat(token: String, id: String, epoch: Long) =
        post("/api/v1/cloud/runner/heartbeat") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(RunnerHeartbeatRequest(id, epoch, ready = true))
        }

    private suspend fun HttpClient.read(id: String) =
        get("/api/v1/cloud/sessions/$id") { bearerAuth("alice") }.body<CloudSessionResponse>()

    private suspend fun HttpResponse.assertRefused(status: HttpStatusCode, type: String) {
        assertEquals(status, this.status, bodyAsText())
        assertTrue("\"$type\"" in bodyAsText(), bodyAsText())
    }

    @Test
    fun aLocalSessionLivesAndDiesWithItsLaunch() = serverTest(listOf(RunnersModule), local) { server ->
        val http = server.client
        // --- Only the member's own, running launch gets a session.
        http.create("alice-laptop", draft.copy(launchId = "lch_" + "f".repeat(32)))
            .assertRefused(HttpStatusCode.NotFound, "launch_not_found")
        http.create("bob-laptop").assertRefused(HttpStatusCode.NotFound, "launch_not_found")
        http.create("alice-laptop", draft.copy(agent = "gemini"))
            .assertRefused(HttpStatusCode.BadRequest, "invalid_agent")
        http.create("alice-laptop", draft.copy(launchId = "run-1"))
            .assertRefused(HttpStatusCode.BadRequest, "invalid_launch")

        // --- Two requests for the same launch at once make one session; a second call returns the first one.
        val opened = coroutineScope { List(2) { async { http.create("alice-laptop") } }.awaitAll() }
        val ids = opened.map { it.body<CloudSessionResponse>().id }.distinct()
        assertEquals(1, ids.size)
        val id = ids.single()
        assertEquals(
            setOf(HttpStatusCode.Created, HttpStatusCode.Accepted),
            (opened.map { it.status } + http.create("alice-desktop").status).toSet(),
        )
        val replay = http.create("alice-laptop", draft.copy(agent = "codex"))
        assertEquals(HttpStatusCode.Accepted to "true", replay.status to replay.headers["Idempotency-Replayed"])
        val created = http.read(id)
        assertEquals("local" to LAUNCH, created.kind to created.launchId)
        assertEquals("creating" to RemoteSessionRules.WAITING_HOST, created.status to created.phase)
        assertEquals(ALICE.toString() to "conversation", created.owner to created.mode)
        assertNull(created.host, "No device is bound")
        assertNull(created.profile)
        assertNull(created.image)

        // --- Listed as its own kind, never among the cloud sessions of a client that does not know it.
        suspend fun list(query: String) =
            http.get("/api/v1/cloud/sessions$query") { bearerAuth("alice") }.body<CloudSessionsResponse>()
                .items.map { it.id }
        assertEquals(listOf(id), list("?kind=local"))
        assertTrue(id !in list(""))
        assertTrue(id !in list("?kind=remote&kind=cloud"))

        // --- It takes no pause, resume, message or GitHub access change: the launch is the session.
        http.post("/api/v1/cloud/sessions/$id/pause") { bearerAuth("alice") }
            .assertRefused(HttpStatusCode.Conflict, "local_session")
        http.post("/api/v1/cloud/sessions/$id/resume") { bearerAuth("alice") }
            .assertRefused(HttpStatusCode.Conflict, "local_session")
        http.put("/api/v1/cloud/sessions/$id/github") {
            bearerAuth("alice-laptop")
            contentType(ContentType.Application.Json)
            setBody("""{"access":"shared"}""")
        }.assertRefused(HttpStatusCode.Conflict, "github_access_unsupported")

        // --- The launch is its host, on whichever device of its owner it runs; another member is not.
        assertEquals(HttpStatusCode.NotFound, http.host("bob-laptop", id).status)
        http.host("alice", id).assertRefused(HttpStatusCode.UnprocessableEntity, "remote_requires_device")
        val hosted = http.host("alice-desktop", id).body<CloudHostResponse>()
        assertEquals(ORG_A.toString() to 1L, hosted.orgId to hosted.epoch)
        val ready = http.beat("alice-laptop", id, 1)
        assertEquals(HttpStatusCode.OK, ready.status, ready.bodyAsText())
        assertEquals("running", ready.body<RunnerHeartbeatResponse>().state)
        http.beat("alice-desktop", id, 2).assertRefused(HttpStatusCode.Conflict, "execution_fenced")
        http.beat("bob-laptop", id, 1).assertRefused(HttpStatusCode.Conflict, "execution_fenced")
        assertEquals("running", http.read(id).status)
        assertNull(http.read(id).host, "A local session never shows a host")

        // --- A clean end: the launch deletes its session when the agent exits.
        val deleted = http.delete("/api/v1/cloud/sessions/$id") { bearerAuth("alice-laptop") }
        assertEquals(HttpStatusCode.Accepted, deleted.status, deleted.bodyAsText())
        assertEquals("terminated", deleted.body<CloudSessionResponse>().desiredStatus)
        http.host("alice-laptop", id).assertRefused(HttpStatusCode.Conflict, "session_not_running")
        // A launch that ended opens no other session.
        running.remove(SECOND_LAUNCH)
        http.create("alice-laptop", draft.copy(launchId = SECOND_LAUNCH))
            .assertRefused(HttpStatusCode.NotFound, "launch_not_found")
    }

    @Test
    fun aLocalCreationTakesNothingButItsLaunchAndAgent() = serverTest(listOf(RunnersModule), local) { server ->
        val http = server.client
        val refusals = listOf(
            draft.copy(repo = "https://github.com/acme/api"),
            draft.copy(ref = "main"),
            draft.copy(profileId = "p"),
            draft.copy(image = "i"),
            draft.copy(directoryName = "ostrom"),
            draft.copy(permissionMode = "bypass"),
            draft.copy(deviceId = "d"),
            draft.copy(folderId = "f"),
            draft.copy(resumeSessionId = "r"),
            draft.copy(prompt = "Fix the tests"),
        )
        for (request in refusals) {
            http.create("alice-laptop", request).assertRefused(HttpStatusCode.BadRequest, "invalid_session")
        }
        http.create("alice-laptop", draft.copy(launchId = null))
            .assertRefused(HttpStatusCode.BadRequest, "invalid_launch")
        val repository = server.koin.get<CloudSessionsRepository>()
        assertEquals(emptyList(), repository.list(ORG_A, ALICE.toString(), null, 10, setOf(SessionKind.LOCAL)))
    }

    @Test
    fun theRepositoryKeepsOneSessionPerLaunchInAnOrganization() = serverTest(listOf(RunnersModule), local) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        fun local(launch: String = LAUNCH, orgId: kotlin.uuid.Uuid = ORG_A) =
            session(createdAt = t0, orgId = orgId).copy(
                kind = SessionKind.LOCAL,
                mode = SessionMode.CONVERSATION,
                launchId = launch,
            )
        val first = local()
        assertTrue(repository.createWithinCap(first, 1))
        assertEquals(first, repository.findLocal(ORG_A, LAUNCH))
        assertEquals(first, repository.get(ORG_A, first.id))
        assertNull(repository.findLocal(ORG_A, SECOND_LAUNCH))
        assertNull(repository.findLocal(ORG_B, LAUNCH), "Inside its organization only")
        val duplicate = runCatchingFailure { repository.createWithinCap(local(), Int.MAX_VALUE) }
        assertTrue(duplicate, "unique(org_id, launch_id)")
        // Another organization's launch of the same id, and a session of no launch, are not held by it.
        assertTrue(repository.createWithinCap(local(orgId = ORG_B), 1))
        // A local session holds no place under the plan's cap, and a session of no launch is held by no index.
        val cloud = session(createdAt = t0)
        assertTrue(repository.createWithinCap(cloud, 1))
        assertTrue(repository.createWithinCap(session(createdAt = t0), Int.MAX_VALUE))
        assertNull(repository.get(ORG_A, cloud.id)?.launchId)
    }

    /** Whether [block] failed (the database's unique index), without swallowing a cancellation. */
    private suspend fun runCatchingFailure(block: suspend () -> Unit): Boolean = try {
        block()
        false
    } catch (error: kotlin.coroutines.cancellation.CancellationException) {
        throw error
    } catch (_: Exception) {
        true
    }

    private companion object {
        const val LAUNCH = "lch_0123456789abcdef0123456789abcdef"
        const val SECOND_LAUNCH = "lch_1123456789abcdef0123456789abcdef"

        /** The test identities' authenticator, which [local] extends. */
        val BASE: TokenAuthenticator = koinApplication { modules(testIdentities) }.koin.get()
    }
}
