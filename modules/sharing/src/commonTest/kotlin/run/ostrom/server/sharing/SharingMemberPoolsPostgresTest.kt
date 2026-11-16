// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.ORG_HEADER
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.MemberPoolRepository
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.sharing.presentation.AccountLimitsDto
import run.ostrom.server.sharing.presentation.ShareAccountRequest
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The members' home pools (`/api/v1/members/{login}/policy`, TeamCode's member policy `poolId`) against the real
 * identity, orgs, secrets and sharing modules on PostgreSQL, every request as the serving role under row-level
 * security: an admin sets a member's pool, the member reads it, a lease keeps to it, and another organization sees
 * and changes nothing.
 */
class SharingMemberPoolsPostgresTest {
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** Tokens of the test: the token is the person's login, resolved to their user id. */
    private val people = mutableMapOf<String, Uuid>()

    /** The changes written to the audit log. */
    private val audited = mutableListOf<OrgAuditRecord>()

    private val overrides = module {
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<OrgAuditTrail> { OrgAuditTrail { audited += it } }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = people[token]?.let {
                    AuthenticatedPrincipal(
                        it.toString(),
                        PrincipalKind.USER,
                        setOf(RelayScopes.ALL_USER),
                        channel = PrincipalChannel.BROWSER,
                        authenticatedAt = Clock.System.now(),
                    )
                }
            }
        }
    }

    private val modules = listOf(IdentityModule, OrgsModule, SecretsModule, SharingModule)

    private fun policy(login: String) = "/api/v1/members/$login/policy"

    /** Kira's organization: liam, a member, and ana, an admin. */
    private class Team(val org: Uuid, val kira: Uuid, val liam: Uuid)

    private suspend fun TestServer.person(login: String): User {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        people[login] = user.id
        return user
    }

    private suspend fun TestServer.organization(slug: String, login: String): Pair<Uuid, Uuid> {
        val org = Org(Uuid.random(), slug, slug.uppercase(), null, null, now)
        val member = Member(Uuid.random(), org.id, person(login).id, OrgRole.OWNER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        return org.id to member.id
    }

    private suspend fun TestServer.join(orgId: Uuid, login: String, role: String = "member"): Uuid {
        val id = Uuid.random()
        val user = person(login)
        koin.get<JdbcDatabase>().withOrganization(orgId) {
            exec(
                "INSERT INTO orgs_members (id, org_id, user_id, role, joined_at) VALUES (?, ?, ?, ?, ?)",
                listOf(
                    UuidColumnType() to id,
                    UuidColumnType() to orgId,
                    UuidColumnType() to user.id,
                    TextColumnType() to role,
                    InstantColumnType() to now,
                ),
            )
        }
        return id
    }

    private suspend fun TestServer.team(): Team {
        val (org, kira) = organization("kira-co", "kira")
        val liam = join(org, "liam")
        join(org, "ana", role = "admin")
        return Team(org, kira, liam)
    }

    private suspend fun TestServer.send(
        method: String,
        login: String?,
        path: String,
        body: JsonObject? = null,
        org: Uuid? = null,
    ): HttpResponse {
        val build: HttpRequestBuilder.() -> Unit = {
            if (login != null) bearerAuth(login)
            if (org != null) header(ORG_HEADER, org.toString())
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        return if (method == "PATCH") client.patch(path, build) else client.get(path, build)
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun pool(value: String) = buildJsonObject { put("poolId", value) }

    private fun assertStatus(expected: HttpStatusCode, response: HttpResponse, what: String) =
        assertEquals(expected, response.status, what)

    /** A Claude account kira shares with its tokens, then puts in [poolId] (none for null); returns its label. */
    private suspend fun TestServer.account(label: String, poolId: String?): String {
        val shared = client.post("/api/v1/accounts") {
            bearerAuth("kira")
            contentType(ContentType.Application.Json)
            setBody(
                ShareAccountRequest(
                    provider = "claude",
                    label = label,
                    email = "$label@example.com",
                    providerAccountId = "acct-$label",
                    accessToken = "sk-ant-oat01-access-$label",
                    refreshToken = "sk-ant-ort01-refresh-$label",
                    expiresAt = (Clock.System.now() + 8.hours).toString(),
                    limits = AccountLimitsDto(),
                ),
            )
        }
        assertStatus(HttpStatusCode.Created, shared, shared.bodyAsText())
        val id = shared.json().getValue("id").jsonPrimitive.content
        if (poolId != null) {
            val moved = send("PATCH", "kira", "/api/v1/accounts/$id", pool(poolId))
            assertStatus(HttpStatusCode.OK, moved, moved.bodyAsText())
        }
        return label
    }

    /** The label of the account [member] of [org] leases now, or null with the refusal's reason. */
    private suspend fun TestServer.leased(org: Uuid, member: Uuid): Pair<String?, String?> =
        when (val result = koin.get<SharedAccountPool>().lease(org, member, AccountProvider.CLAUDE, null)) {
            is LeaseResult.Leased -> (result.lease.account.label to null).also { result.lease.release() }
            is LeaseResult.Refused -> null to result.refusal.reason
        }

    @Test
    fun anAdminSetsAMembersPoolTheMemberReadsItAndTheChangeIsAudited() = serverTest(modules, overrides) { server ->
        with(server) {
            team()
            val none = send("GET", "liam", policy("liam"))
            assertStatus(HttpStatusCode.OK, none, none.bodyAsText())
            assertEquals(buildJsonObject { put("member", "liam") }, none.json(), "No pool: poolId is absent")

            val set = send("PATCH", "ana", policy("liam"), pool("eu"))
            assertStatus(HttpStatusCode.OK, set, set.bodyAsText())
            assertEquals("eu", set.json().getValue("poolId").jsonPrimitive.content)
            assertEquals(set.json(), send("GET", "liam", policy("liam")).json(), "The member reads their own")
            assertEquals(set.json(), send("GET", "kira", policy("liam")).json(), "An owner reads anyone's")
            val record = audited.single()
            assertEquals("policy.updated", record.event)
            assertEquals("member" to "liam", record.subjectType to record.subjectId)
            assertEquals(mapOf("pool_id" to "eu"), record.details)

            val moved = send("PATCH", "kira", policy("liam"), pool("us")).json()
            assertEquals("us", moved.getValue("poolId").jsonPrimitive.content, "An owner moves them")
            val cleared = send("PATCH", "kira", policy("liam"), pool(""))
            assertStatus(HttpStatusCode.OK, cleared, cleared.bodyAsText())
            assertNull(cleared.json()["poolId"], "An empty string clears the pool")
            assertNull(send("GET", "liam", policy("liam")).json()["poolId"])
            assertEquals(emptyMap(), audited.last().details)
        }
    }

    @Test
    fun aMemberChangesNoPoolAndReadsNoOtherMembers() = serverTest(modules, overrides) { server ->
        with(server) {
            team()
            assertStatus(HttpStatusCode.Forbidden, send("PATCH", "liam", policy("liam"), pool("eu")), "own pool")
            assertStatus(HttpStatusCode.Forbidden, send("PATCH", "liam", policy("ana"), pool("eu")), "another's")
            assertStatus(HttpStatusCode.Forbidden, send("GET", "liam", policy("ana")), "another's policy")
            assertNull(send("GET", "liam", policy("liam")).json()["poolId"])
            assertEquals(emptyList(), audited)
        }
    }

    @Test
    fun aMalformedPoolOrAnUnknownMemberIsRefused() = serverTest(modules, overrides) { server ->
        with(server) {
            team()
            person("stranger")
            for (value in listOf("eu west", "a".repeat(37), "eu/1")) {
                val refused = send("PATCH", "kira", policy("liam"), pool(value))
                assertStatus(HttpStatusCode.BadRequest, refused, value)
                val error = refused.json().getValue("error").jsonObject
                assertEquals("invalid_policy", error.getValue("type").jsonPrimitive.content, value)
            }
            assertStatus(
                HttpStatusCode.BadRequest,
                send("PATCH", "kira", policy("liam"), buildJsonObject { }),
                "no poolId",
            )
            mapOf(
                "an unknown login" to send("PATCH", "kira", policy("ghost"), pool("eu")),
                "a person outside the organization" to send("PATCH", "kira", policy("stranger"), pool("eu")),
                "reading an unknown login" to send("GET", "kira", policy("ghost")),
            ).forEach { (what, response) -> assertStatus(HttpStatusCode.NotFound, response, what) }
            assertEquals(emptyList(), audited)
        }
    }

    @Test
    fun aMemberLeasesOnlyTheAccountsOfTheirHomePool() = serverTest(modules, overrides) { server ->
        with(server) {
            val team = team()
            account("none", null)
            account("eu", "eu")
            assertStatus(HttpStatusCode.OK, send("PATCH", "kira", policy("liam"), pool("eu")), "liam to eu")

            repeat(3) { assertEquals("eu" to null, leased(team.org, team.liam), "A member of eu leases only eu") }
            repeat(3) {
                assertEquals("none" to null, leased(team.org, team.kira), "No pool: only the accounts in none")
            }

            assertStatus(HttpStatusCode.OK, send("PATCH", "kira", policy("liam"), pool("asia")), "liam to asia")
            assertEquals(
                null to "No shared claude account is available to you.",
                leased(team.org, team.liam),
                "A pool with no account refuses",
            )
        }
    }

    @Test
    fun anotherOrganizationSeesAndChangesNothing() = serverTest(modules, overrides) { server ->
        with(server) {
            val team = team()
            send("PATCH", "kira", policy("liam"), pool("eu"))
            val (other, _) = organization("zoe-co", "zoe")
            mapOf(
                "read" to send("GET", "zoe", policy("liam"), org = other),
                "change" to send("PATCH", "zoe", policy("liam"), pool("mine"), org = other),
                "read naming the other organization" to send("GET", "zoe", policy("liam"), org = team.org),
                "change naming the other organization" to
                    send("PATCH", "zoe", policy("liam"), pool("mine"), org = team.org),
            ).forEach { (what, response) -> assertStatus(HttpStatusCode.NotFound, response, what) }
            assertEquals("eu", send("GET", "liam", policy("liam")).json().getValue("poolId").jsonPrimitive.content)

            // Row-level security: the other organization's confinement reads none of liam's row, even by its id.
            val pools = koin.get<MemberPoolRepository>()
            assertNull(pools.poolOf(other, team.liam))
            assertEquals(emptyMap(), pools.poolsOf(other, listOf(team.liam)))
            assertEquals(mapOf(team.liam to "eu"), pools.poolsOf(team.org, listOf(team.liam, team.kira)))
            assertEquals(emptyMap(), pools.poolsOf(team.org, emptyList()))
            assertFalse(audited.any { it.orgId == other })
        }
    }

    @Test
    fun everyRouteNeedsACredential() = serverTest(modules, overrides) { server ->
        with(server) {
            mapOf(
                "read" to send("GET", null, policy("liam")),
                "change" to send("PATCH", null, policy("liam"), pool("eu")),
                "an unknown token" to send("GET", "nobody", policy("liam")),
            ).forEach { (what, response) -> assertStatus(HttpStatusCode.Unauthorized, response, what) }
        }
    }
}
