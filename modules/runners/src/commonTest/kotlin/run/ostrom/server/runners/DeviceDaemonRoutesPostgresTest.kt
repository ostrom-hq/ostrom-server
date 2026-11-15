// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.DeviceDaemons
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.SignedInDevices
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.http.docs.API_OPENAPI_PATH
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.runners.domain.RemoteSessionRules
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.ALICE_USER
import run.ostrom.server.runners.fakes.BOB_LAPTOP
import run.ostrom.server.runners.fakes.ContextCipher
import run.ostrom.server.runners.fakes.FakeDeviceDaemons
import run.ostrom.server.runners.fakes.FakeSignedInDevices
import run.ostrom.server.runners.fakes.LAPTOP
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_A_ALLOWED
import run.ostrom.server.runners.fakes.aliceLogin
import run.ostrom.server.runners.presentation.CloudSessionResponse
import run.ostrom.server.runners.presentation.CreateCloudSessionRequest
import run.ostrom.server.runners.presentation.DaemonListingAnswerRequest
import run.ostrom.server.runners.presentation.DaemonPollRequest
import run.ostrom.server.runners.presentation.DaemonPollResponse
import run.ostrom.server.runners.presentation.DeviceFolderDto
import run.ostrom.server.runners.presentation.DeviceFoldersResponse
import run.ostrom.server.runners.presentation.PublishDeviceFoldersRequest
import run.ostrom.server.runners.presentation.RefuseDaemonOrderRequest
import run.ostrom.server.runners.presentation.ResumableSessionDto
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.testing.TestInstanceSettings
import run.ostrom.server.testing.serverTest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The device daemon's routes against a real PostgreSQL (TeamCode's daemon route tests): the daemon of the caller's
 * own device publishes its folders and polls; the owner starts a session on it from the console; the daemon refuses
 * it; nobody else reads its folders or answers its listings.
 */
class DeviceDaemonRoutesPostgresTest {
    private val daemons = FakeDeviceDaemons()

    private val logins: Map<String, AuthenticatedPrincipal> = mapOf(
        "alice-laptop" to aliceLogin("alice-laptop"),
        "alice-desktop" to aliceLogin("alice-desktop"),
        "alice" to AuthenticatedPrincipal(ALICE_USER.toString(), PrincipalKind.USER, setOf("user:*")),
        "reader" to aliceLogin("alice-laptop").copy(scopes = setOf("profile:read")),
    )

    private val daemon = module {
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: kotlin.uuid.Uuid?) =
                    OrgPrincipal(ORG_A, ALICE, OrgRole.MEMBER).takeIf { principal.subjectId == ALICE_USER.toString() }
            }
        }
        single<ConfigSource> { MapConfigSource(emptyMap()) }
        single<InstanceSettings> { TestInstanceSettings(ORG_A_ALLOWED) }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? = logins[token]
            }
        }
        single<SignedInDevices> { FakeSignedInDevices() }
        single<DeviceDaemons> { daemons }
        single<SecretCipher> { ContextCipher }
    }

    private suspend fun HttpClient.poll(token: String) = post("/api/v1/devices/current/daemon/poll") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(DaemonPollRequest(waitMillis = 0, version = "1.0.0", features = listOf("resume.v1")))
    }

    private suspend fun HttpClient.publish(token: String, folders: List<DeviceFolderDto>) =
        put("/api/v1/devices/current/folders") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PublishDeviceFoldersRequest(folders))
        }

    private suspend fun HttpClient.refuse(token: String, id: String) =
        post("/api/v1/devices/current/daemon/sessions/$id/refuse") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(RefuseDaemonOrderRequest("folder_missing"))
        }

    private suspend fun HttpClient.answer(token: String, id: String, answer: DaemonListingAnswerRequest) =
        post("/api/v1/devices/current/daemon/listings/$id") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(answer)
        }

    private suspend fun HttpResponse.assertRefused(status: HttpStatusCode, type: String) {
        assertEquals(status, this.status, bodyAsText())
        assertTrue("\"$type\"" in bodyAsText(), bodyAsText())
    }

    private val folder = DeviceFolderDto("f1", "ostrom", "~/git/ostrom")
    private val start =
        CreateCloudSessionRequest(agent = "claude", kind = "remote", deviceId = LAPTOP.toString(), folderId = "f1")

    private suspend fun HttpClient.folders(token: String = "alice") =
        get("/api/v1/devices/$LAPTOP/folders") { bearerAuth(token) }.body<DeviceFoldersResponse>()

    @Test
    fun theOwnerStartsASessionOnTheDeviceItsDaemonPollsAndRefuses() =
        serverTest(listOf(RunnersModule), daemon) { server ->
            val http = server.client
            assertEquals(listOf(folder), http.publish("alice-laptop", listOf(folder)).body<List<DeviceFolderDto>>())
            val unseen = http.folders()
            assertEquals(
                listOf(LAPTOP.toString(), null, listOf(folder)),
                listOf(unseen.deviceId, unseen.daemon, unseen.folders),
            )
            http.post("/api/v1/cloud/sessions") {
                bearerAuth("alice")
                contentType(ContentType.Application.Json)
                setBody(start)
            }.assertRefused(HttpStatusCode.Conflict, "device_offline")

            val empty = http.poll("alice-laptop").body<DaemonPollResponse>()
            assertEquals(
                listOf(0L, 60_000L, 20_000L),
                listOf(empty.orders.size.toLong(), empty.onlineTtlMillis, empty.maxWaitMillis),
            )
            val seen = http.folders().daemon
            val expected = listOf(true, "1.0.0", listOf("resume.v1"))
            assertEquals(expected, listOf(seen?.online, seen?.version, seen?.features))

            val created = http.post("/api/v1/cloud/sessions") {
                bearerAuth("alice")
                contentType(ContentType.Application.Json)
                setBody(start)
            }
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val session = created.body<CloudSessionResponse>()
            assertEquals(listOf("f1", "ostrom"), listOf(session.host?.folderId, session.host?.directoryName))
            val order = http.poll("alice-laptop").body<DaemonPollResponse>().orders.single()
            assertEquals(listOf(session.id, "f1", "ostrom", "claude", "ask", "creating"), with(order) {
                listOf(sessionId, folderId, directoryName, agent, permissionMode, status)
            })
            assertTrue(http.poll("alice-desktop").body<DaemonPollResponse>().orders.isEmpty(), "Another device's queue")

            http.refuse("alice-desktop", session.id).assertRefused(HttpStatusCode.Conflict, "remote_host_mismatch")
            http.refuse("alice", session.id).assertRefused(HttpStatusCode.UnprocessableEntity, "daemon_requires_device")
            assertEquals(HttpStatusCode.NotFound, http.refuse("alice-laptop", "not-an-id").status)
            val refused = http.refuse("alice-laptop", session.id)
            assertEquals(HttpStatusCode.OK, refused.status, refused.bodyAsText())
            assertTrue(RemoteSessionRules.HOST_REFUSED in refused.bodyAsText())
            http.refuse("alice-laptop", session.id).assertRefused(HttpStatusCode.Conflict, "not_waiting_host")
            val after = http.poll("alice-laptop").body<DaemonPollResponse>()
            assertTrue(after.orders.isEmpty(), "A refused session is no order")

            val stopped = http.delete("/api/v1/devices/current/daemon") { bearerAuth("alice-laptop") }
            assertEquals(HttpStatusCode.NoContent, stopped.status)
            assertNull(http.folders().daemon)
        }

    @Test
    fun theDaemonRoutesAreDescribedWhereTheyAreMounted() = serverTest(listOf(RunnersModule), daemon) { server ->
        val paths = Json.parseToJsonElement(server.client.get(API_OPENAPI_PATH).bodyAsText()).jsonObject
            .getValue("paths").jsonObject
        val described = listOf(
            "/api/v1/devices/current/daemon/poll" to "post",
            "/api/v1/devices/current/daemon" to "delete",
            "/api/v1/devices/current/daemon/sessions/{id}/refuse" to "post",
            "/api/v1/devices/current/daemon/listings/{id}" to "post",
            "/api/v1/devices/current/folders" to "put",
            "/api/v1/devices/{id}/folders" to "get",
        ).map { (path, method) ->
            paths.getValue(path).jsonObject.getValue(method).jsonObject.getValue("operationId").jsonPrimitive.content
        }
        val expected = listOf(
            "pollDeviceDaemon", "stopDeviceDaemon", "refuseDaemonOrder", "answerDaemonListing", "publishDeviceFolders",
            "listDeviceFolders",
        )
        assertEquals(expected, described)
    }

    @Test
    fun theDaemonAnswersOnlyTheListingsOfItsOwnDevice() = serverTest(listOf(RunnersModule), daemon) { server ->
        val http = server.client
        val now = kotlin.time.Clock.System.now()
        val request = daemons.askListing(LAPTOP, "f1", 20, null, now, now + 30.seconds)
        val listed = http.poll("alice-laptop").body<DaemonPollResponse>().listings.single()
        assertEquals(listOf(request.toString(), "f1", 20), listOf(listed.requestId, listed.folderId, listed.limit))
        val page = DaemonListingAnswerRequest(
            listOf(
                ResumableSessionDto("0f3c2b1a-9d8e-4c7b-a6f5-e4d3c2b1a090", "Fix the tests", "2026-10-30T09:00:00Z", 2),
            ),
        )

        http.answer("alice-desktop", request.toString(), page)
            .assertRefused(HttpStatusCode.NotFound, "listing_not_found")
        http.answer("alice-laptop", "not-an-id", page).assertRefused(HttpStatusCode.NotFound, "listing_not_found")
        val undated = page.copy(items = page.items.map { it.copy(lastActiveAt = "today") })
        http.answer("alice-laptop", request.toString(), undated)
            .assertRefused(HttpStatusCode.BadRequest, "invalid_listing")
        assertEquals(HttpStatusCode.NoContent, http.answer("alice-laptop", request.toString(), page).status)
        assertTrue("Fix the tests" in daemons.answers.getValue(request))
    }

    @Test
    fun nobodyElseReadsADevicesFoldersOrUsesItsDaemon() = serverTest(listOf(RunnersModule), daemon) { server ->
        val http = server.client
        http.get("/api/v1/devices/$BOB_LAPTOP/folders") { bearerAuth("alice") }
            .assertRefused(HttpStatusCode.NotFound, "device_not_found")
        http.get("/api/v1/devices/laptop/folders") { bearerAuth("alice") }
            .assertRefused(HttpStatusCode.NotFound, "device_not_found")
        assertEquals(HttpStatusCode.Unauthorized, http.get("/api/v1/devices/$LAPTOP/folders").status)
        assertEquals(HttpStatusCode.Unauthorized, http.poll("nobody").status)
        assertEquals(HttpStatusCode.Forbidden, http.poll("reader").status)
        http.poll("alice").assertRefused(HttpStatusCode.UnprocessableEntity, "daemon_requires_device")
        http.publish("alice", listOf(folder))
            .assertRefused(HttpStatusCode.UnprocessableEntity, "daemon_requires_device")
        http.publish("alice-laptop", listOf(folder, folder)).assertRefused(HttpStatusCode.BadRequest, "invalid_folders")
        assertEquals(HttpStatusCode.Unauthorized, http.answer("nobody", "x", DaemonListingAnswerRequest()).status)
        assertEquals(HttpStatusCode.Unauthorized, http.delete("/api/v1/devices/current/daemon").status)
    }
}
