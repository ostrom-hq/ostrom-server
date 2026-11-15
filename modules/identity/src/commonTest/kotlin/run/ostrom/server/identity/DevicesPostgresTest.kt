// SPDX-License-Identifier: Apache-2.0

package run.ostrom.server.identity

import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.AuditRepository
import run.ostrom.server.identity.domain.DeviceAuthorization
import run.ostrom.server.identity.domain.DeviceAuthorizationStatus
import run.ostrom.server.identity.domain.DeviceAuthorizationsRepository
import run.ostrom.server.identity.domain.DeviceLimitException
import run.ostrom.server.identity.domain.DeviceSignIn
import run.ostrom.server.identity.domain.DeviceSignInRequest
import run.ostrom.server.identity.domain.DevicesRepository
import run.ostrom.server.identity.domain.MAX_REGISTERED_DEVICES
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.CreatePersonalTokenUseCase
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.StartWebSessionUseCase
import run.ostrom.server.identity.domain.StartedSession
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.presentation.AnswerDeviceRequest
import run.ostrom.server.identity.presentation.AuthTokenResponse
import run.ostrom.server.identity.presentation.DeviceCodeRequest
import run.ostrom.server.identity.presentation.DeviceCodeResponse
import run.ostrom.server.identity.presentation.DeviceResponse
import run.ostrom.server.identity.presentation.DeviceTokenRequest
import run.ostrom.server.identity.presentation.DevicesResponse
import run.ostrom.server.identity.presentation.RenameDeviceRequest
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * `devices.v1` end to end on real PostgreSQL: the CLI's login registers its installation, the device is listed to
 * the browser and to the CLI's own sign-in, renamed, read by an owner for a member, and removed with its sessions.
 * The organization is fixed (the orgs module depends on this one): owner Bea, member Ada, in A.
 */
class DevicesPostgresTest {
    private val org = Uuid.parse("00000000-0000-4000-8000-00000000000a")
    private val roles = mutableMapOf<String, Pair<Uuid, OrgRole>>()
    private val memberIds = mutableMapOf<String, Uuid>()

    private val overrides = module {
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                    roles[principal.subjectId]?.let { (member, role) ->
                        OrgPrincipal(member, memberIds.getValue(principal.subjectId), role)
                    }
            }
        }
        single<MemberDirectory> {
            object : MemberDirectory {
                override suspend fun isActiveMember(orgId: Uuid, memberId: String) = userIdOf(orgId, memberId) != null

                override suspend fun userIdOf(orgId: Uuid, memberId: String): Uuid? =
                    memberIds.entries.firstOrNull { it.value.toString() == memberId }?.key?.let(Uuid::parse)
                        ?.takeIf { roles[it.toString()]?.first == orgId }

                override suspend fun hasUser(orgId: Uuid, userId: Uuid) = roles[userId.toString()]?.first == orgId
            }
        }
    }

    private class Refusal(
        val id: String,
        val name: String,
        val platform: String,
        val version: String?,
        val type: String,
    )

    private class Person(val user: User, val session: StartedSession, val memberId: Uuid)

    private suspend fun TestServer.person(subject: String, role: OrgRole?): Person {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, subject, "$subject@example.com", true, subject),
        )
        val memberId = Uuid.random()
        memberIds[user.id.toString()] = memberId
        role?.let { roles[user.id.toString()] = org to it }
        return Person(user, koin.get<StartWebSessionUseCase>()(user.id), memberId)
    }

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun HttpRequestBuilder.signedIn(person: Person) =
        browserSession(person.session.secret, person.session.csrfToken)

    /** `ostrom login` from [installation]: start, approve in the browser, poll. */
    private suspend fun TestServer.login(
        person: Person,
        installation: String?,
        name: String? = "Work laptop",
        platform: String? = "macos",
        osVersion: String? = "15.1",
    ): HttpResponse {
        val codes = client.post("/api/v1/auth/device") {
            json(DeviceCodeRequest("host", null, installation, name, platform, osVersion))
        }
        if (codes.status != HttpStatusCode.OK) return codes
        val started = codes.body<DeviceCodeResponse>()
        val answered = client.post("/api/v1/auth/device/answer") {
            signedIn(person)
            json(AnswerDeviceRequest(started.userCode, true))
        }
        return if (answered.status == HttpStatusCode.NoContent) {
            client.post("/api/v1/auth/device/token") { json(DeviceTokenRequest(started.deviceCode)) }
        } else {
            answered
        }
    }

    private suspend fun TestServer.cliToken(person: Person, installation: String?, name: String? = "Work laptop") =
        login(person, installation, name).body<AuthTokenResponse>().token

    private suspend fun TestServer.byCli(secret: String) =
        client.get(DEVICES) { bearerAuth(secret) }.body<DevicesResponse>().items

    @Test
    fun theLoginRegistersTheInstallationAndTheCliAndTheBrowserListIt() =
        serverTest(listOf(IdentityModule), overrides) { server ->
        val ada = server.person("ada", OrgRole.MEMBER)
        val first = server.cliToken(ada, INSTALLATION)
        val second = server.cliToken(ada, INSTALLATION, name = "Another name")
        val legacy = server.cliToken(ada, null)

        val shown = server.byCli(second)
        val device = shown.first()
        assertEquals(
            listOf("Work laptop", "macos", "15.1", 2, true),
            listOf(device.name, device.platform, device.osVersion, device.activeSessions, device.current),
        )
        assertNotNull(device.createdAt)
        assertNotNull(device.lastLoginAt)
        assertEquals(2, shown.size, "A registered device, then the CLI token from before devices")
        assertNull(shown[1].activeSessions)
        assertEquals(true, server.byCli(first).first().current, "Either sign-in of the device is the current one")
        val browser = server.client.get(DEVICES) { signedIn(ada) }.body<DevicesResponse>().items
        assertEquals(device.id, browser.first().id)
        assertEquals(false, browser.first().current, "A browser session is no device")
        assertEquals(false, server.byCli(legacy).first().current)

        val actions = server.koin.get<AuditRepository>().list(null, ada.user.id, null, 50).map { it.entry.action }
        assertEquals(1, actions.count { it == "device.registered" }, actions.toString())
    }

    @Test
    fun theLoginIsValidatedAndTheDeviceCapIsRefusedAtTheApproval() =
        serverTest(listOf(IdentityModule), overrides) { server ->
        val ada = server.person("ada", OrgRole.MEMBER)
        val refusals = listOf(
            Refusal("NOT-A-UUID", "n", "macos", null, "invalid_installation"),
            Refusal(INSTALLATION, " ", "macos", null, "invalid_device_name"),
            Refusal(INSTALLATION, "n", "beos", null, "invalid_platform"),
            Refusal(INSTALLATION, "n", "macos", "a\tb", "invalid_os_version"),
        )
        for (refusal in refusals) {
            val refused = server.login(ada, refusal.id, refusal.name, refusal.platform, refusal.version)
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue(refused.body<String>().contains(refusal.type), refusal.type)
        }
        // Fifty registered installations, without fifty rate-limited logins.
        val repository = server.koin.get<DeviceAuthorizationsRepository>()
        repeat(MAX_REGISTERED_DEVICES) { index ->
            val authorization = DeviceAuthorization(
                Uuid.random(), "CODE-$index", "host", DeviceAuthorizationStatus.APPROVED, ada.user.id,
                Clock.System.now(), Clock.System.now() + 1.days, null,
            )
            repository.create(authorization, "hash-$index")
            val token = ApiToken(
                Uuid.random(), ada.user.id, null, "host", TokenKind.CLI, setOf("profile:read"), "osc_hint", null,
                Clock.System.now(), Clock.System.now() + 1.days, null, null,
            )
            val login = DeviceSignInRequest("00000000-0000-4000-8000-%012d".format(index), "d$index", null, null)
            assertNotNull(
                repository.consumeOnDevice(authorization.id, token, "secret-$index", login, Clock.System.now()),
            )
        }
        val full = server.login(ada, INSTALLATION)
        assertEquals(HttpStatusCode.Conflict, full.status)
        assertTrue(full.body<String>().contains("device_limit"))
        assertEquals(50, server.client.get(DEVICES) { signedIn(ada) }.body<DevicesResponse>().items.size)
        assertEquals(HttpStatusCode.OK, server.login(ada, "00000000-0000-4000-8000-000000000007").status)
        // The race past the approval is refused at the issue too, and writes nothing.
        val lost = DeviceAuthorization(
            Uuid.random(), "CODE-LOST", "host", DeviceAuthorizationStatus.APPROVED, ada.user.id,
            Clock.System.now(), Clock.System.now() + 1.days, null,
        )
        repository.create(lost, "hash-lost")
        val token = ApiToken(
            Uuid.random(), ada.user.id, null, "host", TokenKind.CLI, setOf("profile:read"), "osc_hint", null,
            Clock.System.now(), Clock.System.now() + 1.days, null, null,
        )
        assertFailsWith<DeviceLimitException> {
            repository.consumeOnDevice(
                lost.id,
                token,
                "secret-lost",
                DeviceSignInRequest(INSTALLATION, "n", null, null),
                Clock.System.now(),
            )
        }
        assertEquals(50, server.client.get(DEVICES) { signedIn(ada) }.body<DevicesResponse>().items.size)
    }

    @Test
    fun aDeviceIsRenamedAndRemovedWithItsSessionsAndOnlyByWhoMayDo() =
        serverTest(listOf(IdentityModule), overrides) { server ->
        val ada = server.person("ada", OrgRole.MEMBER)
        val bea = server.person("bea", OrgRole.OWNER)
        val max = server.person("max", OrgRole.MEMBER)
        val one = server.cliToken(ada, INSTALLATION)
        val two = server.cliToken(ada, INSTALLATION)
        val id = server.byCli(one).first().id

        val renamed = server.client.patch(
            "$DEVICES/$id",
        ) { bearerAuth(one); json(RenameDeviceRequest("Support laptop")) }
        assertEquals(HttpStatusCode.OK, renamed.status)
        assertEquals("Support laptop", renamed.body<DeviceResponse>().name)
        assertEquals(
            HttpStatusCode.BadRequest,
            server.client.patch("$DEVICES/$id") { signedIn(ada); json(RenameDeviceRequest("")) }.status,
        )
        for (stranger in listOf(max, bea)) {
            val refused = server.client.patch("$DEVICES/$id") {
                signedIn(stranger); header("Ostrom-Org", org.toString()); json(RenameDeviceRequest("x"))
            }
            // The owner of the organization the device's owner is in may; a plain member may not.
            val expected = if (stranger === bea) HttpStatusCode.OK else HttpStatusCode.NotFound
            assertEquals(expected, refused.status)
        }
        val garbage = server.client.patch("$DEVICES/not-a-uuid") { signedIn(ada); json(RenameDeviceRequest("x")) }
        assertEquals(HttpStatusCode.NotFound, garbage.status)
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.patch("$DEVICES/${Uuid.random()}") { signedIn(ada); json(RenameDeviceRequest("x")) }.status,
        )

        val members = server.client.get("/api/v1/members/${ada.memberId}/devices") { signedIn(bea) }
        assertEquals(HttpStatusCode.OK, members.status)
        assertEquals(listOf(id), members.body<DevicesResponse>().items.map { it.id })
        assertEquals(
            HttpStatusCode.Forbidden,
            server.client.get("/api/v1/members/${ada.memberId}/devices") { signedIn(max) }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.get("/api/v1/members/${Uuid.random()}/devices") { signedIn(bea) }.status,
        )

        assertEquals(HttpStatusCode.NotFound, server.client.delete("$DEVICES/$id") {
            signedIn(max); header("Ostrom-Org", org.toString())
        }.status)
        assertEquals(HttpStatusCode.NoContent, server.client.delete("$DEVICES/$id") { bearerAuth(one) }.status)
        for (secret in listOf(one, two)) {
            assertEquals(
                HttpStatusCode.Unauthorized,
                server.client.get(DEVICES) { bearerAuth(secret) }.status,
                "Signed out with the device",
            )
        }
        assertTrue(server.client.get(DEVICES) { signedIn(ada) }.body<DevicesResponse>().items.isEmpty())
        val again = server.cliToken(ada, INSTALLATION)
        assertNotEquals(id, server.byCli(again).first().id, "Registered again, under a new id")
    }

    @Test
    fun anAgentOrAServiceOrAPersonalTokenNeverReachesTheDevices() =
        serverTest(listOf(IdentityModule), overrides) { server ->
        val ada = server.person("ada", OrgRole.OWNER)
        val createToken = server.koin.get<CreatePersonalTokenUseCase>()
        val personal = createToken(ada.user.id, "script", listOf("orgs:read"), 1.days).secret
        assertEquals(HttpStatusCode.Forbidden, server.client.get(DEVICES) { bearerAuth(personal) }.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            server.client.patch("$DEVICES/${Uuid.random()}") {
                bearerAuth(personal)
                json(RenameDeviceRequest("x"))
            }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            server.client.delete("$DEVICES/${Uuid.random()}") { bearerAuth(personal) }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            server.client.get("/api/v1/members/${ada.memberId}/devices") { bearerAuth(personal) }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, server.client.get(DEVICES).status)
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/members/${ada.memberId}/devices").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            server.client.patch("$DEVICES/x") { json(RenameDeviceRequest("x")) }.status,
        )
        assertEquals(HttpStatusCode.NotFound, server.client.get("/api/v1/members/${ada.memberId}/devices") {
            signedIn(server.person("nobody", null)); header("Ostrom-Org", org.toString())
        }.status)
    }

    @Test
    fun aDeviceKeepsWhatIsKnownOfItAndTheRepositoriesSayWhenItIsGone() =
        serverTest(listOf(IdentityModule), overrides) { server ->
            val ada = server.person("ada", OrgRole.MEMBER)
            val devices = server.koin.get<DevicesRepository>()
            val authorizations = server.koin.get<DeviceAuthorizationsRepository>()
            val now = Clock.System.now()
            assertFalse(devices.rename(Uuid.random(), "x"))
            assertFalse(devices.remove(ada.user.id, Uuid.random(), now))

            suspend fun signIn(index: Int, platform: String?, osVersion: String?): DeviceSignIn? {
                val authorization = DeviceAuthorization(
                    Uuid.random(), "CODE-$index", "host", DeviceAuthorizationStatus.APPROVED, ada.user.id,
                    now, now + 1.days, null,
                )
                authorizations.create(authorization, "hash-$index")
                val token = ApiToken(
                    Uuid.random(), ada.user.id, null, "host", TokenKind.CLI, setOf("profile:read"), "osc_hint", null,
                    now, now + 1.days, null, null,
                )
                val login = DeviceSignInRequest(INSTALLATION, "d", platform, osVersion)
                val first = authorizations.consumeOnDevice(authorization.id, token, "secret-$index", login, now)
                // The authorization gives one token: a second poll consumes nothing and stores nothing.
                assertNull(authorizations.consumeOnDevice(authorization.id, token, "again-$index", login, now))
                return first
            }

            val registered = assertNotNull(signIn(1, "linux", "6.1"))
            assertTrue(registered.registered)
            val quiet = assertNotNull(signIn(2, null, null))
            assertEquals(registered.device.id, quiet.device.id)
            assertFalse(quiet.registered)
            assertEquals(listOf("linux", "6.1"), listOf(quiet.device.platform, quiet.device.osVersion))
            val updated = assertNotNull(signIn(3, "macos", null))
            assertEquals(listOf("macos", null), listOf(updated.device.platform, updated.device.osVersion))
            val stored = assertNotNull(devices.find(ada.user.id, INSTALLATION))
            assertEquals(listOf("d", "macos", null), listOf(stored.name, stored.platform, stored.osVersion))
            assertEquals(1, devices.count(ada.user.id))
            // The devices of remote sessions, read in one query; an unknown id is none.
            assertEquals(listOf(stored), devices.getAll(listOf(stored.id, stored.id, Uuid.random())))
            assertEquals(emptyList(), devices.getAll(emptyList()))
        }

    private companion object {
        const val DEVICES = "/api/v1/devices"
        const val INSTALLATION = "6f1c0c3e-9a40-4a39-8e0e-2b1f3f8a1a11"
    }
}
