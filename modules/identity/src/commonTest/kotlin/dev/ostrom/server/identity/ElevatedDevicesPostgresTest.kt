// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.ADMIN_SCOPES
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.IssueCliTokenUseCase
import dev.ostrom.server.identity.domain.ListAuditEventsUseCase
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.presentation.AnswerDeviceRequest
import dev.ostrom.server.identity.presentation.AuthTokenResponse
import dev.ostrom.server.identity.presentation.DeviceCodeRequest
import dev.ostrom.server.identity.presentation.DeviceCodeResponse
import dev.ostrom.server.identity.presentation.DeviceResponse
import dev.ostrom.server.identity.presentation.DeviceTokenRequest
import dev.ostrom.server.identity.presentation.DevicesResponse
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.ORG_HEADER
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
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
    fun onlyItsOwnerSignsItOutInItsOrganizationAndItStopsAtOnce() =
        serverTest(listOf(IdentityModule), overrides) { server ->
            val ada = server.person("ada", orgA, OrgRole.OWNER)
            val al = server.person("al", orgA, OrgRole.ADMIN)
            val adasSecret = server.adminLogin(ada)
            val adas = server.idOf(adasSecret)
            val als = server.idOf(server.adminLogin(al))

            assertEquals(HttpStatusCode.NotFound, server.signOut(ada, adas), "Outside its organization it is unknown")
            assertEquals(HttpStatusCode.NotFound, server.signOut(ada, als, orgA), "Someone else's")
            assertEquals(HttpStatusCode.NotFound, server.signOut(ada, "nope", orgA))
            assertEquals(HttpStatusCode.NoContent, server.signOut(ada, adas, orgA))
            assertNull(server.koin.get<TokenAuthenticator>().authenticate(adasSecret), "Signed out at once")
            assertEquals(HttpStatusCode.NotFound, server.signOut(ada, adas, orgA), "Already signed out")
            assertEquals(listOf(als), server.devices(ada, orgA).map { it.id })

            val events = server.koin.get<ListAuditEventsUseCase>()(orgA, null, null, 20).map { it.entry }
            val revoked = events.single { it.action == "token.revoked" }
            assertEquals(ada.user.id to adas, revoked.actorId to revoked.targetId)
            assertEquals("admin", revoked.details["kind"])
        }

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
    }
}
