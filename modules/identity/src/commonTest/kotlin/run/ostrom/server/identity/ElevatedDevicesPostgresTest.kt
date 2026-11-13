// SPDX-License-Identifier: Apache-2.0

package run.ostrom.server.identity

import run.ostrom.server.identity.domain.ADMIN_SCOPES
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.IssueCliTokenUseCase
import run.ostrom.server.identity.domain.ListAuditEventsUseCase
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
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.ORG_HEADER
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The tokens of `ostrom login --admin` in `/devices`, end to end: real HTTP, real PostgreSQL under the serving role
 * (row-level security of `identity_api_tokens`), tokens issued by the real device flow. Each is bound to an
 * organization and only read inside it, so it is listed and signed out when the request names that organization in
 * `Ostrom-Org`. The organizations and roles come from a fixed resolver that honours the header like the orgs module:
 * owner Ada and admin Al (A), member Max (A), owner Bea (B).
 */
class ElevatedDevicesPostgresTest {
    private val orgA = Uuid.parse("00000000-0000-4000-8000-0000000000a2")
    private val orgB = Uuid.parse("00000000-0000-4000-8000-0000000000b2")
    private val roles = mutableMapOf<String, Pair<Uuid, OrgRole>>()

    private val resolver = object : PrincipalOrgResolver {
        override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? {
            val (org, role) = roles[principal.subjectId]?.takeIf { principal.kind == PrincipalKind.USER } ?: return null
            val named = principal.namedOrg
            return OrgPrincipal(org, Uuid.random(), role).takeIf { named == null || named == org.toString() }
        }
    }
    private val overrides = module { single<PrincipalOrgResolver> { resolver } }

    private class Person(val user: User, val session: StartedSession)

    private suspend fun TestServer.person(subject: String, org: Uuid, role: OrgRole): Person {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, subject, "$subject@example.com", true, subject),
        )
        roles[user.id.toString()] = org to role
        return Person(user, koin.get<StartWebSessionUseCase>()(user.id))
    }

    private fun HttpRequestBuilder.signedIn(person: Person, org: Uuid? = null) {
        browserSession(person.session.secret, person.session.csrfToken)
        org?.let { header(ORG_HEADER, it.toString()) }
    }

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    /** `ostrom login --admin`, approved in the browser by [person]: the `osg_` secret bound to their organization. */
    private suspend fun TestServer.adminLogin(person: Person): String {
        val codes = client.post("/api/v1/auth/device") {
            json(DeviceCodeRequest("laptop --admin", ADMIN_SCOPES.toList()))
        }.body<DeviceCodeResponse>()
        val answered = client.post("/api/v1/auth/device/answer") {
            signedIn(person)
            json(AnswerDeviceRequest(codes.userCode, true))
        }
        assertEquals(HttpStatusCode.NoContent, answered.status)
        val token = client.post("/api/v1/auth/device/token") { json(DeviceTokenRequest(codes.deviceCode)) }
            .body<AuthTokenResponse>().token
        assertTrue(token.startsWith("osg_"))
        return token
    }

    private suspend fun TestServer.devices(person: Person, org: Uuid? = null): List<DeviceResponse> =
        client.get(DEVICES) { signedIn(person, org) }.body<DevicesResponse>().items

    private suspend fun TestServer.signOut(person: Person, id: String, org: Uuid? = null): HttpStatusCode =
        client.delete("$DEVICES/$id") { signedIn(person, org) }.status

    private suspend fun TestServer.idOf(secret: String): String =
        assertNotNull(koin.get<TokenAuthenticator>().authenticate(secret)?.credentialId)

    @Test
    fun anAdminLoginsTokenIsListedInItsOrganizationWithItsScopesAndExpiry() =
        serverTest(listOf(IdentityModule), overrides) { server ->
            val ada = server.person("ada", orgA, OrgRole.OWNER)
            val al = server.person("al", orgA, OrgRole.ADMIN)
            val max = server.person("max", orgA, OrgRole.MEMBER)
            val laptop = server.koin.get<IssueCliTokenUseCase>()(ada.user.id, "laptop").token.id.toString()
            val adas = server.idOf(server.adminLogin(ada))
            val als = server.idOf(server.adminLogin(al))

            assertEquals(listOf(laptop), server.devices(ada).map { it.id }, "Nothing names an organization")
            val inA = server.devices(ada, orgA)
            assertEquals(laptop, inA.first().id, "The CLI tokens first")
            assertEquals(setOf(als, adas), inA.drop(1).map { it.id }.toSet(), "An owner sees every member's")
            assertEquals("cli", inA.first().kind)
            assertNull(inA.first().orgId)
            val elevated = inA.single { it.id == adas }
            assertEquals("admin" to orgA.toString(), elevated.kind to elevated.orgId)
            assertTrue(assertNotNull(elevated.scopes).containsAll(ADMIN_SCOPES), "${elevated.scopes}")
            assertNotNull(elevated.expiresAt)
            assertEquals(setOf(als, adas), server.devices(al, orgA).map { it.id }.toSet(), "An admin too")
            assertTrue(server.devices(max, orgA).isEmpty(), "A member sees only their own, and has none")
        }

    @Test
    fun anotherOrganizationNeitherSeesNorSignsOutTheTokens() =
        serverTest(listOf(IdentityModule), overrides) { server ->
            val ada = server.person("ada", orgA, OrgRole.OWNER)
            val bea = server.person("bea", orgB, OrgRole.OWNER)
            val adas = server.idOf(server.adminLogin(ada))
            val beas = server.idOf(server.adminLogin(bea))

            assertEquals(listOf(beas), server.devices(bea, orgB).map { it.id })
            assertEquals(HttpStatusCode.NotFound, server.client.get(DEVICES) { signedIn(bea, orgA) }.status)
            assertEquals(HttpStatusCode.NotFound, server.signOut(bea, adas, orgA), "Not a member of A")
            assertEquals(HttpStatusCode.NotFound, server.signOut(bea, adas, orgB), "Not a token of B")
            assertEquals(HttpStatusCode.NotFound, server.signOut(ada, beas, orgA))
            assertEquals(listOf(adas), server.devices(ada, orgA).map { it.id })
            assertEquals(listOf(beas), server.devices(bea, orgB).map { it.id })
        }

    @Test
    fun aMemberSignsOutOnlyTheirOwnInItsOrganizationAndItStopsAtOnce() =
        serverTest(listOf(IdentityModule), overrides) { server ->
            val al = server.person("al", orgA, OrgRole.ADMIN)
            val max = server.person("max", orgA, OrgRole.ADMIN)
            val maxSecret = server.adminLogin(max)
            val maxs = server.idOf(maxSecret)
            val als = server.idOf(server.adminLogin(al))
            roles[max.user.id.toString()] = orgA to OrgRole.MEMBER // demoted since, the token still live

            assertEquals(HttpStatusCode.NotFound, server.signOut(max, maxs), "Outside its organization it is unknown")
            assertEquals(HttpStatusCode.NotFound, server.signOut(max, als, orgA), "Another member's, for a member")
            assertEquals(HttpStatusCode.NotFound, server.signOut(max, "nope", orgA))
            assertEquals(HttpStatusCode.NoContent, server.signOut(max, maxs, orgA))
            assertNull(server.koin.get<TokenAuthenticator>().authenticate(maxSecret), "Signed out at once")
            assertEquals(HttpStatusCode.NotFound, server.signOut(max, maxs, orgA), "Already signed out")
            assertEquals(listOf(als), server.devices(al, orgA).map { it.id })

            val revoked = server.revocations().single()
            assertEquals(max.user.id to maxs, revoked.actorId to revoked.targetId)
            assertEquals("admin" to max.user.id.toString(), revoked.details["kind"] to revoked.details["owner"])
        }

    @Test
    fun anOwnerOrAnAdminSignsOutAnyMembersTokenAuditedWithItsOwner() =
        serverTest(listOf(IdentityModule), overrides) { server ->
            val ada = server.person("ada", orgA, OrgRole.OWNER)
            val al = server.person("al", orgA, OrgRole.ADMIN)
            val alsSecret = server.adminLogin(al)
            val als = server.idOf(alsSecret)
            val adas = server.idOf(server.adminLogin(ada))

            assertEquals(HttpStatusCode.NoContent, server.signOut(ada, als, orgA), "The owner signs out the admin's")
            assertNull(server.koin.get<TokenAuthenticator>().authenticate(alsSecret), "A leaked token stops at once")
            assertEquals(HttpStatusCode.NoContent, server.signOut(al, adas, orgA), "An admin signs out the owner's")
            assertTrue(server.devices(ada, orgA).isEmpty())

            val revocations = server.revocations().associateBy { it.targetId }
            val ofAl = revocations.getValue(als)
            assertEquals(ada.user.id to al.user.id.toString(), ofAl.actorId to ofAl.details["owner"])
            val ofAda = revocations.getValue(adas)
            assertEquals(al.user.id to ada.user.id.toString(), ofAda.actorId to ofAda.details["owner"])
        }

    private suspend fun TestServer.revocations() = koin.get<ListAuditEventsUseCase>()(orgA, null, null, MAX_EVENTS)
        .map { it.entry }.filter { it.action == "token.revoked" }

    @Test
    fun withoutABrowserSessionTheDevicesOfAnOrganizationAreRefused() =
        serverTest(listOf(IdentityModule), overrides) { server ->
            val ada = server.person("ada", orgA, OrgRole.OWNER)
            val secret = server.adminLogin(ada)
            val id = server.idOf(secret)
            val named: HttpRequestBuilder.() -> Unit = { header(ORG_HEADER, orgA.toString()) }
            assertEquals(HttpStatusCode.Unauthorized, server.client.get(DEVICES, named).status)
            assertEquals(HttpStatusCode.Unauthorized, server.client.delete("$DEVICES/$id", named).status)
            val byToken = server.client.get(DEVICES) { bearerAuth(secret); named() }
            assertEquals(HttpStatusCode.Forbidden, byToken.status, "The elevated token itself is not a session")
            val signOutByToken = server.client.delete("$DEVICES/$id") { bearerAuth(secret); named() }
            assertEquals(HttpStatusCode.Forbidden, signOutByToken.status)
            assertEquals(listOf(id), server.devices(ada, orgA).map { it.id })
        }

    private companion object {
        const val DEVICES = "/api/v1/devices"
        const val MAX_EVENTS = 20
    }
}
