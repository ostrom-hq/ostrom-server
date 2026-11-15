// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.DeviceRemovalListener
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.SignedInDevices
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.runners.fakes.ORG_A_ALLOWED
import run.ostrom.server.runners.fakes.TestOrgResolver
import run.ostrom.server.testing.TestInstanceSettings
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.PermissionBypass
import run.ostrom.server.runners.domain.RemoteHost
import run.ostrom.server.runners.domain.RemoteSessionRules
import run.ostrom.server.runners.domain.SessionKind
import run.ostrom.server.runners.domain.SessionMode
import run.ostrom.server.runners.domain.ResumeOutcome
import run.ostrom.server.runners.domain.SessionRef
import run.ostrom.server.runners.infrastructure.inTenant
import io.github.smyrgeorge.sqlx4k.Statement
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.DESKTOP
import run.ostrom.server.runners.fakes.FakeSignedInDevices
import run.ostrom.server.runners.fakes.LAPTOP
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.REMOTE_LOGINS
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.presentation.CloudHostResponse
import run.ostrom.server.runners.presentation.CloudSessionResponse
import run.ostrom.server.runners.presentation.CloudSessionsResponse
import run.ostrom.server.runners.presentation.CreateCloudSessionRequest
import run.ostrom.server.runners.presentation.RunnerHeartbeatRequest
import run.ostrom.server.runners.presentation.RunnerHeartbeatResponse
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Remote sessions against a real PostgreSQL (TeamCode's
 * `remoteSessionsAreCreatedOnTheCallersDeviceAndHostedWithItsOwnLogin`): created on the caller's device, hosted with
 * its own sign-in, listed by kind, ended with their device.
 */
class RemoteSessionsPostgresTest {
    private val devices = FakeSignedInDevices()

    /** The test identities, with the CLI sign-ins of [REMOTE_LOGINS] and their devices. */
    private val remote = module {
        // testIdentities' definitions, its authenticator extended (an included one would win over this module's).
        single<PrincipalOrgResolver> { TestOrgResolver }
        single<ConfigSource> { MapConfigSource(emptyMap()) }
        single<InstanceSettings> { TestInstanceSettings(ORG_A_ALLOWED) }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? =
                    REMOTE_LOGINS[token] ?: BASE.authenticate(token)
            }
        }
        single<SignedInDevices> { devices }
    }

    private val t0 = Instant.parse("2026-10-30T10:00:00Z")

    private suspend fun HttpClient.create(token: String, request: CreateCloudSessionRequest, key: String? = null) =
        post("/api/v1/cloud/sessions") {
            bearerAuth(token)
            if (key != null) header("Idempotency-Key", key)
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

    private suspend fun HttpResponse.assertRefused(status: HttpStatusCode, type: String) {
        assertEquals(status, this.status)
        assertTrue("\"$type\"" in bodyAsText(), bodyAsText())
    }

    private val draft = CreateCloudSessionRequest(agent = "claude", kind = "remote", directoryName = "ostrom-server")

    @Test
    fun remoteSessionsAreCreatedOnTheCallersDeviceAndHostedWithItsOwnLogin() =
        serverTest(listOf(RunnersModule), remote) { server ->
            val http = server.client
            val created = http.create("alice-laptop", draft.copy(prompt = "Fix the tests", permissionMode = "bypass"))
            assertEquals(HttpStatusCode.Created, created.status)
            val session = created.body<CloudSessionResponse>()
            assertEquals("remote", session.kind)
            assertEquals("conversation", session.mode)
            assertEquals("creating", session.status)
            assertEquals(RemoteSessionRules.WAITING_HOST, session.phase)
            assertEquals(ALICE.toString(), session.owner)
            assertEquals(true, session.permissionBypass)
            assertEquals(60, session.limits.idleTimeoutMinutes)
            val shown = assertNotNull(session.host)
            assertEquals(LAPTOP.toString(), shown.deviceId)
            assertEquals("Alice's laptop", shown.deviceName)
            assertEquals("ostrom-server", shown.directoryName)
            assertEquals("bypass", shown.permissionMode)
            assertEquals(false, shown.online)

            // Hosted from its device: a new epoch each time, the one before fenced.
            val first = http.host("alice-laptop", session.id).body<CloudHostResponse>()
            assertEquals(ORG_A.toString(), first.orgId)
            assertEquals(1, first.epoch)
            assertEquals(RemoteSessionRules.STARTING_HOST, first.session.phase)
            val second = http.host("alice-laptop", session.id).body<CloudHostResponse>()
            assertEquals(2, second.epoch)
            http.beat("alice-laptop", session.id, 1).assertRefused(HttpStatusCode.Conflict, "execution_fenced")
            val ready = http.beat("alice-laptop", session.id, 2)
            assertEquals(HttpStatusCode.OK, ready.status, ready.bodyAsText())
            assertEquals("running", ready.body<RunnerHeartbeatResponse>().state)
            val running = http.get("/api/v1/cloud/sessions/${session.id}") { bearerAuth("alice") }
                .body<CloudSessionResponse>()
            assertEquals("running", running.status)
            assertEquals(true, running.host?.online)
            // Another device of the owner's, another member, a browser session: no host.
            // A device's sign-in is a host's credential, fenced for a session it does not host; anything else is none.
            http.beat("alice-desktop", session.id, 2).assertRefused(HttpStatusCode.Conflict, "execution_fenced")
            assertEquals(HttpStatusCode.Unauthorized, http.beat("alice", session.id, 2).status)
            http.host("alice-desktop", session.id).assertRefused(HttpStatusCode.Conflict, "remote_host_mismatch")
            http.host("alice", session.id).assertRefused(HttpStatusCode.UnprocessableEntity, "remote_requires_device")
            assertEquals(HttpStatusCode.NotFound, http.host("bob-laptop", session.id).status)
            assertEquals(HttpStatusCode.NotFound, http.host("alice-laptop", "not-an-id").status)
            // No hour of the plan is counted for it.
            val month = kotlin.time.Clock.System.now().toString().take(7)
            assertEquals(0, server.koin.get<CloudSessionsRepository>().usageSeconds(ORG_A, month))
        }

    @Test
    fun onlyAnInteractiveSignInCreatesARemoteSessionAndOnlyForItsOwnDevice() =
        serverTest(listOf(RunnersModule), remote) { server ->
            val http = server.client
            http.create("alice", draft).assertRefused(HttpStatusCode.UnprocessableEntity, "remote_requires_device")
            http.create("alice-old", draft).assertRefused(HttpStatusCode.UnprocessableEntity, "remote_requires_device")
            http.create("reader", draft).let { assertEquals(HttpStatusCode.Forbidden, it.status) }
            http.create("alice-laptop", draft.copy(deviceId = DESKTOP.toString(), folderId = "f"))
                .assertRefused(HttpStatusCode.BadRequest, "device_target_unsupported")
            http.create("alice-laptop", draft.copy(repo = "https://github.com/acme/api"))
                .assertRefused(HttpStatusCode.BadRequest, "invalid_session")
            http.create("alice-laptop", draft.copy(mode = "task"))
                .assertRefused(HttpStatusCode.BadRequest, "invalid_mode")
            http.create("alice-laptop", draft.copy(mode = "conversation", directoryName = "../etc"))
                .assertRefused(HttpStatusCode.BadRequest, "invalid_directory_name")
            http.create("alice-laptop", draft.copy(kind = "pod"))
                .assertRefused(HttpStatusCode.BadRequest, "invalid_kind")
            http.create("alice-laptop", draft.copy(launchId = "lch_0123456789abcdef0123456789abcdef"))
                .assertRefused(HttpStatusCode.BadRequest, "invalid_session")
            http.create("alice", CreateCloudSessionRequest(agent = "claude", directoryName = "x"))
                .assertRefused(HttpStatusCode.BadRequest, "invalid_session")
            http.create("alice", CreateCloudSessionRequest(agent = "claude", resumeSessionId = "r"))
                .assertRefused(HttpStatusCode.BadRequest, "invalid_session")
            http.create("alice", CreateCloudSessionRequest(agent = "claude", launchId = "l"))
                .assertRefused(HttpStatusCode.BadRequest, "invalid_session")
            // A cloud session is not hosted.
            val cloud = http.create("alice", CreateCloudSessionRequest(agent = "claude", kind = "cloud"))
            assertEquals(HttpStatusCode.Created, cloud.status)
            val cloudId = cloud.body<CloudSessionResponse>().also { assertNull(it.kind); assertNull(it.host) }.id
            http.host("alice-laptop", cloudId).assertRefused(HttpStatusCode.Conflict, "not_remote")

            // The same key replays the same request.
            val keyed = http.create("alice-laptop", draft, key = "remote-1")
            assertEquals(HttpStatusCode.Created, keyed.status)
            val replay = http.create("alice-laptop", draft, key = "remote-1")
            assertEquals(HttpStatusCode.Accepted, replay.status)
            assertEquals(keyed.body<CloudSessionResponse>().id, replay.body<CloudSessionResponse>().id)
        }

    @Test
    fun theListShowsRemoteSessionsOnlyWhenAskedForThem() = serverTest(listOf(RunnersModule), remote) { server ->
        val http = server.client
        val remoteId = http.create("alice-laptop", draft).body<CloudSessionResponse>().id
        val cloudId = http.create("alice", CreateCloudSessionRequest(agent = "claude")).body<CloudSessionResponse>().id
        devices.names.remove(LAPTOP)

        suspend fun list(query: String) = http.get("/api/v1/cloud/sessions$query") { bearerAuth("alice") }

        assertEquals(listOf(cloudId), list("").body<CloudSessionsResponse>().items.map { it.id })
        val remotes = list("?kind=remote").body<CloudSessionsResponse>().items
        assertEquals(listOf(remoteId), remotes.map { it.id })
        // A device the identity module no longer knows shows no name.
        assertNull(remotes.single().host?.deviceName)
        assertEquals(
            setOf(remoteId, cloudId),
            list("?kind=remote&kind=cloud").body<CloudSessionsResponse>().items.map { it.id }.toSet(),
        )
        list("?kind=pod").assertRefused(HttpStatusCode.BadRequest, "invalid_kind")
    }

    @Test
    fun theRepositoryKeepsAHostAndEndsTheSessionsOfARemovedDevice() =
        serverTest(listOf(RunnersModule), remote) { server ->
            val repository = server.koin.get<CloudSessionsRepository>()
            fun remote() = session(createdAt = t0).copy(
                kind = SessionKind.REMOTE,
                mode = SessionMode.CONVERSATION,
                host = RemoteHost(LAPTOP, "ostrom", RemoteSessionRules.BYPASS),
                permissionBypass = PermissionBypass("alice", "credential-alice-laptop", t0),
            )
            val live = remote()
            val ended = remote().copy(state = SessionState.TERMINATED, desiredState = DesiredState.TERMINATED)
            val elsewhere = remote().copy(host = RemoteHost(DESKTOP, null, RemoteSessionRules.ASK))
            val cloud = session(createdAt = t0)
            for (s in listOf(live, ended, elsewhere, cloud)) assertTrue(repository.createWithinCap(s, 1))
            // Remote sessions hold no place under the plan's cap.
            assertTrue(repository.createWithinCap(session(createdAt = t0, orgId = ORG_B), 1))

            assertEquals(live, repository.get(ORG_A, live.id))
            assertEquals(elsewhere, repository.getById(elsewhere.id))
            assertEquals(cloud, repository.get(ORG_A, cloud.id))
            assertEquals(
                setOf(SessionRef(ORG_A, live.id), SessionRef(ORG_A, ended.id)),
                repository.hostedOn(LAPTOP).toSet(),
            )
            assertEquals(listOf(SessionRef(ORG_A, live.id)), repository.hostedOn(LAPTOP, live = true))
            assertEquals(
                listOf(cloud.id),
                repository.list(ORG_A, "alice", null, 10).map { it.id },
            )
            assertEquals(
                setOf(live.id, ended.id, elsewhere.id),
                repository.list(ORG_A, "alice", null, 10, setOf(SessionKind.REMOTE)).map { it.id }.toSet(),
            )

            server.koin.getAll<DeviceRemovalListener>().forEach { it.removed(ALICE, LAPTOP) }

            val after = assertNotNull(repository.get(ORG_A, live.id))
            assertEquals(DesiredState.TERMINATED, after.desiredState)
            assertEquals(RemoteSessionRules.DEVICE_REMOVED, after.terminatedReason)
            assertNull(after.host?.deviceId)
            assertEquals("ostrom", after.host?.directoryName)
            assertNull(repository.get(ORG_A, ended.id)?.host?.deviceId)
            assertEquals(SessionState.TERMINATED, repository.get(ORG_A, ended.id)?.state)
            assertEquals(DESKTOP, repository.get(ORG_A, elsewhere.id)?.host?.deviceId)
            assertEquals(emptyList(), repository.hostedOn(LAPTOP))
            // A second removal finds nothing to end.
            assertEquals(false, repository.endOnRemovedDevice(SessionRef(ORG_A, live.id), LAPTOP, t0))

            // A remote session resumes whatever the cap: the cloud one holds the only place.
            val paused = remote().copy(state = SessionState.PAUSED, desiredState = DesiredState.PAUSED)
            assertTrue(repository.createWithinCap(paused, 10))
            assertTrue(repository.resumeWithinCap(ORG_A, paused.id, 1, t0) is ResumeOutcome.Resumed)
            // A kind this version does not know is read as a cloud session's.
            server.driver.inTenant(ORG_A) {
                execute(
                    Statement.create("UPDATE runners_sessions SET kind = 'pod' WHERE id = :id").bind("id", paused.id),
                ).getOrThrow()
            }
            assertEquals(SessionKind.CLOUD, repository.get(ORG_A, paused.id)?.kind)
        }

    private companion object {
        /** The test identities' authenticator, which [remote] extends. */
        val BASE: TokenAuthenticator = koinApplication { modules(testIdentities) }.koin.get()
    }
}
