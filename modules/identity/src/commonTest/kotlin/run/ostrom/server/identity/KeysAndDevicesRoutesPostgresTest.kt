// SPDX-License-Identifier: AGPL-3.0-only

package run.ostrom.server.identity

import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.domain.CreatePersonalTokenUseCase
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.IssueCliTokenUseCase
import run.ostrom.server.identity.domain.ListAuditEventsUseCase
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.StartWebSessionUseCase
import run.ostrom.server.identity.domain.StartedSession
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.presentation.CreateServiceKeyRequest
import run.ostrom.server.identity.presentation.DeviceResponse
import run.ostrom.server.identity.presentation.DevicesResponse
import run.ostrom.server.identity.presentation.ServiceKeyResponse
import run.ostrom.server.identity.presentation.ServiceKeysResponse
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
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
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * Service keys and devices end to end: real HTTP, real PostgreSQL under the serving role (row-level security of
 * `identity_api_tokens`), real credentials. The organizations and roles come from a fixed resolver (the orgs module
 * depends on this one): owners Ada (A) and Bea (B), member Max (A).
 */
class KeysAndDevicesRoutesPostgresTest {
    private val orgA = Uuid.parse("00000000-0000-4000-8000-00000000000a")
    private val orgB = Uuid.parse("00000000-0000-4000-8000-00000000000b")
    private val roles = mutableMapOf<String, Pair<Uuid, OrgRole>>()

    private val resolver = object : PrincipalOrgResolver {
        override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
            roles[principal.subjectId]?.takeIf { principal.kind == PrincipalKind.USER }
                ?.let { (org, role) -> OrgPrincipal(org, Uuid.random(), role) }
    }
    private val overrides = module { single<PrincipalOrgResolver> { resolver } }

    private suspend fun TestServer.person(subject: String, org: Uuid?, role: OrgRole = OrgRole.OWNER): Person {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, subject, "$subject@example.com", true, subject),
        )
        org?.let { roles[user.id.toString()] = it to role }
        return Person(user, koin.get<StartWebSessionUseCase>()(user.id))
    }

    private class Person(val user: User, val session: StartedSession)

    private fun HttpRequestBuilder.signedIn(person: Person) =
        browserSession(person.session.secret, person.session.csrfToken)

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private val ciKey = CreateServiceKeyRequest("CI", listOf("sessions:write"), expiresInDays = 30)

    private suspend fun TestServer.keys(person: Person): List<ServiceKeyResponse> =
        client.get(KEYS) { signedIn(person) }.body<ServiceKeysResponse>().items

    private suspend fun TestServer.devices(person: Person): List<DeviceResponse> =
        client.get(DEVICES) { signedIn(person) }.body<DevicesResponse>().items

    private suspend fun TestServer.deleting(path: String, person: Person): HttpStatusCode =
        client.delete(path) { signedIn(person) }.status

    private suspend fun TestServer.authenticate(secret: String) = koin.get<TokenAuthenticator>().authenticate(secret)

    @Test
    fun anOwnerCreatesListsAndDeletesTheOrganizationsKeysAndOnlyTheHashIsStored() =
        serverTest(listOf(IdentityModule), overrides) { server ->
            val ada = server.person("ada", orgA)
            val created = server.client.post(KEYS) { signedIn(ada); json(ciKey) }
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("no-store", created.headers[HttpHeaders.CacheControl])
            val key = created.body<ServiceKeyResponse>()
            val secret = assertNotNull(key.secret)
            assertTrue(secret.startsWith("oss_"))
            assertNotNull(key.expiresAt)

            assertEquals(listOf(key.copy(secret = null)), server.keys(ada), "Listed without its secret")
            val hash = server.koin.get<SecretHasher>().hash(secret)
            val stored = assertNotNull(server.koin.get<ApiTokensRepository>().findBySecretHash(hash))
            assertEquals(orgA to TokenKind.SERVICE, stored.orgId to stored.kind)

            // The key authenticates as a service of organization A, never as Ada.
            val principal = assertNotNull(server.authenticate(secret))
            assertEquals(PrincipalKind.SERVICE to orgA.toString(), principal.kind to principal.orgId)
            assertEquals(key.id, principal.subjectId)
            val byKey = server.client.get(KEYS) { bearerAuth(secret) }
            assertEquals(HttpStatusCode.Forbidden, byKey.status, "A key is not a person")

            assertEquals(HttpStatusCode.NoContent, server.deleting("$KEYS/${key.id}", ada))
            assertNull(server.authenticate(secret), "Deleted: it stops at once")
            assertTrue(server.keys(ada).isEmpty())
            assertEquals(HttpStatusCode.NotFound, server.deleting("$KEYS/${key.id}", ada))
            assertEquals(HttpStatusCode.NotFound, server.deleting("$KEYS/nope", ada))

            val actions = server.koin.get<ListAuditEventsUseCase>()(orgA, null, null, 10).map { it.entry.action }
            assertTrue("service_key.created" in actions && "service_key.deleted" in actions, actions.toString())
        }

    @Test
    fun anotherOrganizationNeitherSeesNorDeletesTheKeys() =
        serverTest(listOf(IdentityModule), overrides) { server ->
            val ada = server.person("ada", orgA)
            val bea = server.person("bea", orgB)
            val key = server.client.post(KEYS) { signedIn(ada); json(ciKey) }.body<ServiceKeyResponse>()
            assertTrue(server.keys(bea).isEmpty())
            assertEquals(HttpStatusCode.NotFound, server.deleting("$KEYS/${key.id}", bea))
            assertEquals(listOf(key.id), server.keys(ada).map { it.id })
        }

    @Test
    fun aMemberATokenOrSomeoneWithoutOrganizationIsRefused() =
        serverTest(listOf(IdentityModule), overrides) { server ->
            val max = server.person("max", orgA, OrgRole.MEMBER)
            val nobody = server.person("nobody", null)
            for (path in listOf(KEYS, DEVICES)) {
                assertEquals(HttpStatusCode.Unauthorized, server.client.get(path).status)
                assertEquals(HttpStatusCode.Unauthorized, server.client.delete("$path/x").status)
            }
            assertEquals(HttpStatusCode.Unauthorized, server.client.post(KEYS) { json(ciKey) }.status)

            assertEquals(HttpStatusCode.Forbidden, server.client.get(KEYS) { signedIn(max) }.status)
            val byMember = server.client.post(KEYS) { signedIn(max); json(ciKey) }
            assertEquals(HttpStatusCode.Forbidden, byMember.status)
            assertEquals(HttpStatusCode.Forbidden, server.deleting("$KEYS/${Uuid.random()}", max))
            assertEquals(HttpStatusCode.NotFound, server.client.get(KEYS) { signedIn(nobody) }.status)

            val personal = server.koin.get<CreatePersonalTokenUseCase>()(
                max.user.id, "script", listOf("orgs:read"), 1.days,
            ).secret
            assertEquals(HttpStatusCode.Forbidden, server.client.get(KEYS) { bearerAuth(personal) }.status)
            val minted = server.client.post(KEYS) { bearerAuth(personal); json(ciKey) }
            assertEquals(HttpStatusCode.Forbidden, minted.status, "A token never mints a key (S2-52)")
            assertEquals(HttpStatusCode.Forbidden, server.client.get(DEVICES) { bearerAuth(personal) }.status)
            val bad = server.client.post(KEYS) {
                signedIn(server.person("own", orgA)); json(CreateServiceKeyRequest("k", listOf("gates:answer")))
            }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
        }

    @Test
    fun aPersonSeesAndSignsOutTheirOwnWorkstationsOnly() =
        serverTest(listOf(IdentityModule), overrides) { server ->
            val ada = server.person("ada", orgA)
            val bea = server.person("bea", orgB)
            val cli = server.koin.get<IssueCliTokenUseCase>()
            val laptop = cli(ada.user.id, "laptop")
            val beasLaptop = cli(bea.user.id, "bea's laptop")
            server.koin.get<CreatePersonalTokenUseCase>()(ada.user.id, "script", listOf("orgs:read"), null)

            val listed = server.client.get(DEVICES) { signedIn(ada) }
            assertEquals("no-store", listed.headers[HttpHeaders.CacheControl])
            val adas = listed.body<DevicesResponse>().items
            assertEquals(listOf(laptop.token.id.toString() to "laptop"), adas.map { it.id to it.name })

            val laptopPath = "$DEVICES/${laptop.token.id}"
            assertEquals(HttpStatusCode.NotFound, server.deleting(laptopPath, bea), "Someone else's device is unknown")
            assertEquals(HttpStatusCode.NotFound, server.deleting("$DEVICES/nope", ada))
            assertEquals(HttpStatusCode.NoContent, server.deleting(laptopPath, ada))
            assertNull(server.authenticate(laptop.secret), "Signed out at once")
            assertTrue(server.devices(ada).isEmpty())
            assertNotNull(server.authenticate(beasLaptop.secret))
            assertEquals(1, server.devices(bea).size)
        }

    private companion object {
        const val KEYS = "/api/v1/service-keys"
        const val DEVICES = "/api/v1/devices"
    }
}
