// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
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
import run.ostrom.server.kernel.sharing.SharedAccountSpend
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
import run.ostrom.server.sharing.domain.PoolBudgetRepository
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.sharing.domain.SharedAccountRepository
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
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * TeamCode's shared overflow pools (`/api/v1/pools/budgets`, the member policy's `allowSharedOverflow`) against the
 * real identity, orgs, secrets and sharing modules on PostgreSQL, every request as the serving role under row-level
 * security: an admin shares a reserve, a member allowed overflow borrows it once their home pool is exhausted and until
 * its budget is spent, home accounts first, a member not allowed never, and another organization sees and changes
 * nothing. The spend of the day is the usage ledger's port, faked here (the usage module's tests read it for real).
 */
class SharingOverflowPostgresTest {
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
    private val people = mutableMapOf<String, Uuid>()
    private val audited = mutableListOf<OrgAuditRecord>()

    /** What each shared account served today, and the reads of it. */
    private val spent = mutableMapOf<Uuid, Long>()
    private var spendReads = 0

    private val base = module {
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
    private val overrides = module {
        includes(base)
        single<SharedAccountSpend> {
            SharedAccountSpend { _, ids, _ ->
                spendReads++
                spent.filterKeys { it in ids }
            }
        }
    }

    private val modules = listOf(IdentityModule, OrgsModule, SecretsModule, SharingModule)

    private val budgets = "/api/v1/pools/budgets"

    private fun policy(login: String) = "/api/v1/members/$login/policy"

    /** Kira's organization: liam, a member, and ana, an admin. */
    private class Team(val org: Uuid, val liam: Uuid, val ana: Uuid)

    private suspend fun TestServer.person(login: String): Uuid {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        people[login] = user.id
        return user.id
    }

    private suspend fun TestServer.organization(slug: String, login: String): Uuid {
        val org = Org(Uuid.random(), slug, slug.uppercase(), null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, person(login), OrgRole.OWNER, now)) {
            false
        }
        return org.id
    }

    private suspend fun TestServer.join(orgId: Uuid, login: String, role: String): Uuid {
        val id = Uuid.random()
        val user = person(login)
        koin.get<JdbcDatabase>().withOrganization(orgId) {
            exec(
                "INSERT INTO orgs_members (id, org_id, user_id, role, joined_at) VALUES (?, ?, ?, ?, ?)",
                listOf(
                    UuidColumnType() to id,
                    UuidColumnType() to orgId,
                    UuidColumnType() to user,
                    TextColumnType() to role,
                    InstantColumnType() to now,
                ),
            )
        }
        return id
    }

    private suspend fun TestServer.team(): Team {
        val org = organization("kira-co", "kira")
        return Team(org, join(org, "liam", "member"), join(org, "ana", "admin"))
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
        return when (method) {
            "PATCH" -> client.patch(path, build)
            "PUT" -> client.put(path, build)
            else -> client.get(path, build)
        }
    }

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())

    private suspend fun HttpResponse.errorType() =
        json().jsonObject.getValue("error").jsonObject.getValue("type").jsonPrimitive.content

    private fun budget(pool: String, micros: Long, shared: Boolean) = buildJsonObject {
        put("poolId", pool)
        put("dailyMicros", micros)
        put("sharedOverflow", shared)
    }

    private fun member(pool: String, overflow: Boolean? = null) = buildJsonObject {
        put("poolId", pool)
        if (overflow != null) put("allowSharedOverflow", overflow)
    }

    private fun assertStatus(expected: HttpStatusCode, response: HttpResponse, what: String) =
        assertEquals(expected, response.status, what)

    /** A Claude account kira shares in [poolId]; its id. */
    private suspend fun TestServer.account(label: String, poolId: String): Uuid {
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
        val id = shared.json().jsonObject.getValue("id").jsonPrimitive.content
        assertStatus(HttpStatusCode.OK, send("PATCH", "kira", "/api/v1/accounts/$id", member(poolId)), "pool")
        return Uuid.parse(id)
    }

    private suspend fun TestServer.cool(org: Uuid, account: Uuid) =
        koin.get<SharedAccountRepository>().setCooldown(org, account, Clock.System.now() + 1.hours)

    /** The label of the account [member] leases now, a call priced at [estimate], or null when refused. */
    private suspend fun TestServer.leased(org: Uuid, member: Uuid, estimate: Long = 1): String? {
        val result = koin.get<SharedAccountPool>().lease(org, member, AccountProvider.CLAUDE, null) { estimate }
        return when (result) {
            is LeaseResult.Leased -> result.lease.account.label.also { result.lease.release() }
            is LeaseResult.Refused -> null
        }
    }

    @Test
    fun anAdminSharesAReserveAndTheChangeIsAudited() = serverTest(modules, overrides) { server ->
        with(server) {
            team()
            assertEquals(JsonArray(emptyList()), send("GET", "kira", budgets).json(), "No pool budget yet")
            val saved = send("PUT", "ana", budgets, budget("reserve", 5_000_000, true))
            assertStatus(HttpStatusCode.OK, saved, saved.bodyAsText())
            assertEquals(budget("reserve", 5_000_000, true), saved.json())
            val plain = buildJsonObject {
                put("poolId", "eu")
                put("dailyMicros", 1)
            }
            assertEquals(budget("eu", 1, false), send("PUT", "kira", budgets, plain).json(), "Not shared by default")
            assertEquals(
                listOf("eu", "reserve"),
                send("GET", "kira", budgets).json().jsonArray.map {
                    it.jsonObject.getValue("poolId").jsonPrimitive.content
                },
            )
            val record = audited.first()
            assertEquals("pool_budget.updated", record.event)
            assertEquals("pool" to "reserve", record.subjectType to record.subjectId)
            assertEquals(mapOf("daily_micros" to "5000000", "shared_overflow" to "true"), record.details)
        }
    }

    @Test
    fun aMemberAllowedOverflowBorrowsTheReserveUntilItsBudgetIsSpentAndHomeComesFirst() =
        serverTest(modules, overrides) { server ->
            with(server) {
                val team = team()
                val home = account("home", "eu")
                val reserve = account("reserve", "reserve")
                send("PUT", "kira", budgets, budget("reserve", 1_000, true))
                val allowed = send("PATCH", "kira", policy("liam"), member("eu", overflow = true))
                assertStatus(HttpStatusCode.OK, allowed, allowed.bodyAsText())
                assertEquals(
                    buildJsonObject {
                        put("member", "liam")
                        put("poolId", "eu")
                        put("allowSharedOverflow", true)
                    },
                    allowed.json(),
                )
                assertEquals(mapOf("pool_id" to "eu", "allow_shared_overflow" to "true"), audited.last().details)
                send("PATCH", "kira", policy("ana"), member("eu"))

                repeat(3) { assertEquals("home", leased(team.org, team.liam), "Both open: home first") }
                cool(team.org, home)
                assertEquals("reserve", leased(team.org, team.liam, estimate = 1_000), "Home exhausted: the reserve")
                assertEquals(null, leased(team.org, team.ana), "Not allowed: never the reserve")

                spent[reserve] = 999
                assertEquals("reserve", leased(team.org, team.liam), "1 micro left covers a call of 1")
                assertEquals(null, leased(team.org, team.liam, estimate = 2), "Not one of 2")
                spent[reserve] = 1_000
                assertEquals(null, leased(team.org, team.liam), "The day's spend reached the budget: closed")

                // Absent, the overflow is kept; false withdraws it.
                send("PATCH", "kira", policy("liam"), member("eu"))
                val kept = koin.get<MemberPoolRepository>().policyOf(team.org, team.liam)
                assertEquals(true, kept.allowSharedOverflow)
                spent.clear()
                assertEquals("reserve", leased(team.org, team.liam))
                send("PATCH", "kira", policy("liam"), member("eu", overflow = false))
                assertEquals(null, leased(team.org, team.liam))
            }
        }

    @Test
    fun aReserveIsNeverAPrimaryPoolInEitherDirection() = serverTest(modules, overrides) { server ->
        with(server) {
            team()
            send("PATCH", "kira", policy("liam"), member("eu"))
            val primary = send("PUT", "kira", budgets, budget("eu", 1_000, true))
            assertStatus(HttpStatusCode.Conflict, primary, "eu is liam's home")
            assertEquals("overflow_primary_pool", primary.errorType())
            assertStatus(HttpStatusCode.OK, send("PUT", "kira", budgets, budget("eu", 1_000, false)), "unshared")

            send("PUT", "kira", budgets, budget("reserve", 1_000, true))
            val home = send("PATCH", "kira", policy("ana"), member("reserve", overflow = true))
            assertStatus(HttpStatusCode.Conflict, home, "a reserve as a home pool")
            assertEquals("overflow_primary_pool", home.errorType())
            assertEquals(null, send("GET", "ana", policy("ana")).json().jsonObject["poolId"], "Nothing written")
        }
    }

    @Test
    fun atMostSixteenPoolsAreShared() = serverTest(modules, overrides) { server ->
        with(server) {
            team()
            repeat(16) {
                assertStatus(HttpStatusCode.OK, send("PUT", "kira", budgets, budget("r$it", 1, true)), "$it")
            }
            val limit = send("PUT", "kira", budgets, budget("r16", 1, true))
            assertStatus(HttpStatusCode.Conflict, limit, "a 17th")
            assertEquals("overflow_pool_limit", limit.errorType())
            assertStatus(HttpStatusCode.OK, send("PUT", "kira", budgets, budget("r3", 2, true)), "one of the 16")
            assertStatus(HttpStatusCode.OK, send("PUT", "kira", budgets, budget("r16", 1, false)), "not shared")
            assertEquals(17, send("GET", "kira", budgets).json().jsonArray.size)
        }
    }

    @Test
    fun aBudgetOutOfItsFormIsRefused() = serverTest(modules, overrides) { server ->
        with(server) {
            team()
            for (body in listOf(
                budget("eu", 0, false),
                budget("eu", 100_000_000_001, false),
                budget("eu west", 1, false),
                budget("", 1, false),
            )) {
                val refused = send("PUT", "kira", budgets, body)
                assertStatus(HttpStatusCode.BadRequest, refused, body.toString())
                assertEquals("invalid_pool_budget", refused.errorType(), body.toString())
            }
            val home = send("PUT", "kira", budgets, budget("", 1, true))
            assertStatus(HttpStatusCode.BadRequest, home, "the home pool shared")
            assertEquals("invalid_overflow_pool", home.errorType())
            assertStatus(HttpStatusCode.BadRequest, send("PUT", "kira", budgets, buildJsonObject { }), "no pool")
            assertEquals(emptyList(), audited)
        }
    }

    @Test
    fun aMemberNeitherReadsNorChangesTheBudgets() = serverTest(modules, overrides) { server ->
        with(server) {
            team()
            assertStatus(HttpStatusCode.Forbidden, send("GET", "liam", budgets), "read")
            assertStatus(HttpStatusCode.Forbidden, send("PUT", "liam", budgets, budget("eu", 1, false)), "change")
            assertStatus(HttpStatusCode.Unauthorized, send("GET", null, budgets), "no credential")
            assertStatus(HttpStatusCode.Unauthorized, send("PUT", null, budgets, budget("eu", 1, false)), "no one")
            assertStatus(HttpStatusCode.Unauthorized, send("GET", "nobody", budgets), "an unknown token")
            assertEquals(emptyList(), audited)
        }
    }

    @Test
    fun anotherOrganizationSeesAndChangesNoneOfTheBudgets() = serverTest(modules, overrides) { server ->
        with(server) {
            val team = team()
            send("PUT", "kira", budgets, budget("reserve", 1_000, true))
            val other = organization("zoe-co", "zoe")
            assertEquals(JsonArray(emptyList()), send("GET", "zoe", budgets, org = other).json(), "Its own: none")
            mapOf(
                "read naming the other organization" to send("GET", "zoe", budgets, org = team.org),
                "change naming the other organization" to
                    send("PUT", "zoe", budgets, budget("reserve", 1, false), org = team.org),
            ).forEach { (what, response) -> assertStatus(HttpStatusCode.NotFound, response, what) }
            send("PUT", "zoe", budgets, budget("reserve", 7, false), org = other)
            assertEquals(budget("reserve", 1_000, true), send("GET", "kira", budgets).json().jsonArray.single())

            // Row-level security: the other organization's confinement reads none of the reserve.
            val repository = koin.get<PoolBudgetRepository>()
            assertEquals(emptyList(), repository.reserves(other))
            assertEquals(listOf("reserve"), repository.reserves(team.org).map { it.poolId })
            assertFalse(audited.any { it.orgId == other && it.subjectId != "reserve" })
            assertEquals(0, spendReads, "No lease, no spend read")
        }
    }

    @Test
    fun withoutTheLedgerAReserveLendsItsWholeBudget() = serverTest(modules, base) { server ->
        with(server) {
            val team = team()
            account("reserve", "reserve")
            send("PUT", "kira", budgets, budget("reserve", 1, true))
            send("PATCH", "kira", policy("liam"), member("eu", overflow = true))
            assertEquals("reserve", leased(team.org, team.liam))
        }
    }
}
