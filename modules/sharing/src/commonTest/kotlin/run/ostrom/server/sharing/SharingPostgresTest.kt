// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.sharing.domain.AccountChangedException
import run.ostrom.server.sharing.domain.AccountCredentials
import run.ostrom.server.sharing.domain.AccountOwnedByAnotherMemberException
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountRefreshRepository
import run.ostrom.server.sharing.domain.AccountSpendReader
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.AccountTokens
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.OAuthRefresher
import run.ostrom.server.sharing.domain.RefreshResult
import run.ostrom.server.sharing.domain.RefreshWrite
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.sharing.domain.SharedAccountRepository
import run.ostrom.server.sharing.presentation.AccountLimitsDto
import run.ostrom.server.sharing.presentation.CapacityPoolDto
import run.ostrom.server.sharing.presentation.CapacityResponse
import run.ostrom.server.sharing.presentation.ShareAccountRequest
import run.ostrom.server.sharing.presentation.SharedAccountResponse
import run.ostrom.server.sharing.presentation.SharedAccountsResponse
import run.ostrom.server.sharing.presentation.UpdateAccountRequest
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Real PostgreSQL and HTTP: sharing, tokens sealed at rest and never returned, neutral labels, scopes, row-level
 * security between orgs.
 */
class SharingPostgresTest {
    private val byToken = mapOf(
        "admin-a" to Sharing.adminA,
        "member-a" to Sharing.memberA,
        "other-a" to Sharing.otherA,
        "admin-b" to Sharing.adminB,
        "agent-a" to Sharing.memberA,
        "device-a" to Sharing.memberA,
        "reader-a" to Sharing.memberA,
    )

    /** Browser sessions hold every right of the user; the others hold what they list. */
    private val principals = mapOf(
        "agent-a" to AuthenticatedPrincipal("agent-a", PrincipalKind.SERVICE, setOf(RelayScopes.AGENT_SESSION)),
        "device-a" to AuthenticatedPrincipal("device-a", PrincipalKind.DEVICE, setOf(RelayScopes.ALL_USER)),
        "reader-a" to AuthenticatedPrincipal("reader-a", PrincipalKind.USER, setOf(RelayScopes.ACCOUNTS_READ)),
    )

    private val authenticator = module {
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = principals[token]
                    ?: byToken[token]?.let {
                        val scopes = setOf(RelayScopes.ALL_USER)
                        AuthenticatedPrincipal(
                            token, PrincipalKind.USER, scopes, channel = PrincipalChannel.BROWSER,
                            authenticatedAt = Clock.System.now(),
                        )
                    }
            }
        }
    }

    private val overrides: Module = module {
        includes(authenticator)
        single<CallerResolver> {
            object : CallerResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
                    byToken[principal.subjectId]?.takeIf { orgId == null || it.orgId == orgId }
            }
        }
    }

    private val base = "/api/v1/accounts"
    private val modules = listOf(SecretsModule, SharingModule)

    private suspend fun TestServer.shareAs(
        token: String,
        id: String = "acct-uuid-1",
        label: String = "Lyes Max 20x",
        expiresAt: String = (Clock.System.now() + 8.hours).toString(),
    ): HttpResponse = client.post(base) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(
            ShareAccountRequest(
                provider = "claude",
                label = label,
                email = "lyes@example.com",
                providerAccountId = id,
                accessToken = "sk-ant-oat01-secret-access",
                refreshToken = "sk-ant-ort01-secret-refresh",
                expiresAt = expiresAt,
                limits = AccountLimitsDto(maxConcurrent = 4),
            ),
        )
    }

    @Test
    fun aSharedAccountIsSealedAtRestAndLeasedByThePool() = serverTest(modules, overrides) { server ->
        with(server) {
            val created = shareAs("member-a")
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            assertFalse("secret" in created.bodyAsText(), "Tokens never come back")
            val account = created.body<SharedAccountResponse>()
            assertTrue(account.mine)
            val stored = driver.fetchInOrg(Sharing.orgA, Statement.create("SELECT envelope FROM sharing_accounts"))
                .getOrThrow().single().get("envelope").asString()
            assertFalse("secret" in stored, "Sealed at rest")

            val lease = koin.get<SharedAccountPool>()
                .lease(Sharing.orgA, Sharing.otherA.memberId, AccountProvider.CLAUDE, "session-1")
            val leased = assertIs<LeaseResult.Leased>(lease).lease
            assertEquals("sk-ant-oat01-secret-access", leased.credentials.accessToken)
            val reset = (Clock.System.now() + 3.hours).epochSeconds
            leased.observe(
                200,
                mapOf(
                    "anthropic-ratelimit-unified-5h-utilization" to "0.25",
                    "anthropic-ratelimit-unified-7d-utilization" to "0.5",
                    "anthropic-ratelimit-unified-7d-reset" to "$reset",
                ),
            )
            leased.observe(200, mapOf("anthropic-ratelimit-unified-5h-utilization" to "0.30"))
            leased.release()

            val seenByOther = client.get(base) { bearerAuth("other-a") }.body<SharedAccountsResponse>().items.single()
            assertEquals("Account 1", seenByOther.label)
            assertEquals(null, seenByOther.email)
            assertEquals(null, seenByOther.providerAccountId)
            assertEquals("", seenByOther.owner)
            assertEquals(
                listOf("five_hour" to 0.30, "seven_day" to 0.5),
                seenByOther.windows.map {
                    it.window to
                        it.utilization
                },
            )
            val seenByAdmin = client.get(base) { bearerAuth("admin-a") }.body<SharedAccountsResponse>().items.single()
            assertEquals("Lyes Max 20x", seenByAdmin.label)
            assertEquals(Sharing.memberA.memberId.toString(), seenByAdmin.owner)
            assertEquals("ready", seenByAdmin.status)
            assertEquals(4, seenByAdmin.limits.maxConcurrent)
            val capacity = client.get("/api/v1/capacity") { bearerAuth("other-a") }.body<CapacityResponse>()
            assertEquals(listOf(CapacityPoolDto("claude", 1, 1, 0)), capacity.pools)
            assertEquals("Account 1", capacity.accounts.single().label)
        }
    }

    @Test
    fun ownersAndAdminsChangeAndRemoveOthersCannot() = serverTest(modules, overrides) { server ->
        with(server) {
            val account = shareAs("member-a").body<SharedAccountResponse>()
            val path = "$base/${account.id}"
            assertEquals(HttpStatusCode.NotFound, patchAs("other-a", path, UpdateAccountRequest(paused = true)).status)
            assertEquals(HttpStatusCode.Conflict, shareAs("other-a").status, "Another member cannot take it over")
            val off = patchAs("member-a", path, UpdateAccountRequest(paused = true, priority = 5))
                .body<SharedAccountResponse>()
            assertEquals("disabled", off.status)
            assertTrue(off.paused)
            val restricted = patchAs(
                "member-a",
                path,
                UpdateAccountRequest(sharedWith = listOf(Sharing.otherA.memberId.toString())),
            ).body<SharedAccountResponse>()
            assertEquals(listOf(Sharing.otherA.memberId.toString()), restricted.sharedWith)
            assertEquals(4, restricted.limits.maxConcurrent, "Other limits kept")
            val badMembers = patchAs("member-a", path, UpdateAccountRequest(sharedWith = listOf("nope")))
            assertEquals(HttpStatusCode.BadRequest, badMembers.status)
            val open = patchAs("admin-a", path, UpdateAccountRequest(sharedWith = emptyList()))
                .body<SharedAccountResponse>()
            assertEquals(emptyList(), open.sharedWith, "An empty list opens the account to the whole org")
            val limited = patchAs(
                "admin-a",
                path,
                UpdateAccountRequest(limits = AccountLimitsDto(maxConcurrent = 2).patch()),
            )
                .body<SharedAccountResponse>()
            assertEquals(2, limited.limits.maxConcurrent)
            val notVisible = patchAs("other-a", path, UpdateAccountRequest(sharedWith = emptyList()))
            assertEquals(HttpStatusCode.NotFound, notVisible.status)
            val missing = patchAs("admin-a", "$base/${Uuid.random()}", UpdateAccountRequest(sharedWith = emptyList()))
            assertEquals(HttpStatusCode.NotFound, missing.status)
            val both = patchAs(
                "admin-a",
                path,
                UpdateAccountRequest(
                    limits = AccountLimitsDto(maxConcurrent = 3).patch(),
                    sharedWith = listOf(Sharing.memberA.memberId.toString()),
                ),
            ).body<SharedAccountResponse>()
            assertEquals(3, both.limits.maxConcurrent)
            assertEquals(listOf(Sharing.memberA.memberId.toString()), both.sharedWith)
            val capacity = client.get("/api/v1/capacity") { bearerAuth("admin-a") }.body<CapacityResponse>()
            assertEquals(listOf(CapacityPoolDto("claude", 1, 0, 0)), capacity.pools, "A paused account is not ready")
            assertIs<LeaseResult.Refused>(
                koin.get<SharedAccountPool>().lease(
                    Sharing.orgA,
                    Sharing.memberA.memberId,
                    AccountProvider.CLAUDE,
                    null,
                ),
                "A paused account serves no one",
            )
            assertEquals(HttpStatusCode.NoContent, client.delete(path) { bearerAuth("admin-a") }.status)
            assertEquals(HttpStatusCode.NotFound, client.delete(path) { bearerAuth("admin-a") }.status)
            assertEquals(HttpStatusCode.NotFound, client.delete("$base/not-an-id") { bearerAuth("admin-a") }.status)
        }
    }

    /** Only the fields set away from their defaults, as a client changing those alone sends them. */
    private fun AccountLimitsDto.patch() = Json.encodeToJsonElement(AccountLimitsDto.serializer(), this).jsonObject

    @Test
    fun aChangeOrASecondShareKeepsTheLimitsItDoesNotName() = serverTest(modules, overrides) { server ->
        with(server) {
            val account = shareAs("member-a").body<SharedAccountResponse>()
            val path = "$base/${account.id}"
            val other = Sharing.otherA.memberId.toString()
            val restricted =
                AccountLimitsDto(maxConcurrent = 4, monthlyBudgetMicros = 7, allowedMembers = listOf(other))
            assertEquals(
                HttpStatusCode.OK,
                patchAs("admin-a", path, UpdateAccountRequest(limits = restricted.patch())).status,
            )
            val concurrency = patchAs(
                "admin-a",
                path,
                UpdateAccountRequest(limits = AccountLimitsDto(maxConcurrent = 2).patch()),
            )
                .body<SharedAccountResponse>().limits
            assertEquals(
                restricted.copy(maxConcurrent = 2),
                concurrency,
                "Changing one limit keeps the budget and members",
            )
            val reshared = shareAs("member-a").body<SharedAccountResponse>()
            assertEquals(account.id, reshared.id)
            assertEquals(restricted.copy(maxConcurrent = 2), reshared.limits, "Sharing again keeps the admin's limits")
            val cleared = patchAs(
                "admin-a",
                path,
                UpdateAccountRequest(limits = Json.parseToJsonElement("""{"monthlyBudgetMicros":null}""").jsonObject),
            ).body<SharedAccountResponse>().limits
            assertNull(cleared.monthlyBudgetMicros, "An explicit null clears the budget")
            for (bad in listOf("""{"maxConcurrent":null}""", """{"nope":1}""", """{"maxConcurrent":"x"}""")) {
                val refused =
                    patchAs("admin-a", path, UpdateAccountRequest(limits = Json.parseToJsonElement(bad).jsonObject))
                assertEquals(HttpStatusCode.BadRequest, refused.status, bad)
            }
        }
    }

    @Test
    fun aRetiredAccountStaysRetiredAndRacingSharesKeepAnEnvelopeThatOpens() = serverTest(modules, overrides) { server ->
        with(server) {
            val account = shareAs("member-a").body<SharedAccountResponse>()
            val path = "$base/${account.id}"
            val repository = koin.get<SharedAccountRepository>()
            val id = Uuid.parse(account.id)
            repository.setStatus(Sharing.orgA, id, AccountStatus.RECONNECT_REQUIRED)
            patchAs("member-a", path, UpdateAccountRequest(paused = true))
            val resumed = patchAs("member-a", path, UpdateAccountRequest(paused = false)).body<SharedAccountResponse>()
            assertEquals("reconnect_required", resumed.status, "Pausing and resuming never revives dead tokens")
            val stale = repository.get(Sharing.orgA, id)!!.copy(status = AccountStatus.READY)
            assertEquals(AccountStatus.RECONNECT_REQUIRED, repository.update(stale)!!.status, "Nor does a stale write")
            repository.setStatus(Sharing.orgA, id, AccountStatus.WRONG_ACCOUNT)
            val foreign = patchAs("member-a", path, UpdateAccountRequest(paused = false)).body<SharedAccountResponse>()
            assertEquals("wrong_account", foreign.status, "Nor tokens of another account")
            val staleForeign = repository.get(Sharing.orgA, id)!!.copy(status = AccountStatus.READY)
            assertEquals(AccountStatus.WRONG_ACCOUNT, repository.update(staleForeign)!!.status)
            repository.setStatus(Sharing.orgA, id, AccountStatus.RECONNECT_REQUIRED)

            // Two first shares of one provider account: the second keeps the first row, and its envelope still opens.
            val first = repository.get(Sharing.orgA, id)!!
            val cipher = koin.get<AccountTokenCipher>()
            val racer = first.copy(id = Uuid.random(), status = AccountStatus.READY)
            val credentials = AccountCredentials("racer-access", "racer-refresh", Clock.System.now() + 8.hours)
            val stored = repository.upsert(racer, cipher.seal(credentials, Sharing.orgA, racer.id))
            assertEquals(id, stored.id)
            assertNotNull(cipher.open(repository.envelope(Sharing.orgA, id)!!, Sharing.orgA, id))
            val stranger = first.copy(id = Uuid.random(), ownerMemberId = Sharing.otherA.memberId)
            assertFailsWith<AccountOwnedByAnotherMemberException> {
                repository.upsert(stranger, cipher.seal(credentials, Sharing.orgA, stranger.id))
            }
        }
    }

    @Test
    fun sharesEditsAndStatusesBumpTheVersionAndAConditionalWriteNeverOverwritesALaterOne() =
        serverTest(modules, overrides) { server ->
            with(server) {
                val id = Uuid.parse(shareAs("member-a").body<SharedAccountResponse>().id)
                val repository = koin.get<SharedAccountRepository>()
                val read = repository.get(Sharing.orgA, id)!!
                assertEquals(1L, read.version)
                repository.setCooldown(Sharing.orgA, id, Clock.System.now())
                koin.get<AccountRefreshRepository>().locked(Sharing.orgA, id) {
                    RefreshWrite.Reseal(it!!.envelope) to Unit
                }
                assertEquals(1L, repository.get(Sharing.orgA, id)!!.version, "The pool's routine writes do not count")
                repository.setStatus(Sharing.orgA, id, AccountStatus.READY)
                assertNull(
                    repository.update(read.copy(label = "Stale"), expectedVersion = 1),
                    "Never over a later write",
                )
                assertEquals(3L, repository.update(read.copy(label = "Fresh"), expectedVersion = 2)!!.version)
                val envelope = repository.envelope(Sharing.orgA, id)!!
                assertFailsWith<AccountChangedException> { repository.upsert(read, envelope, expectedVersion = 2) }
                assertFailsWith<AccountChangedException>("Not new any more") {
                    repository.upsert(read, envelope, expectedVersion = 0)
                }
                assertEquals("Fresh", repository.get(Sharing.orgA, id)!!.label, "Nothing overwritten")
                assertEquals(4L, repository.upsert(read, envelope, expectedVersion = 3).version)
                assertTrue(repository.delete(Sharing.orgA, id))
                assertFailsWith<AccountChangedException>("Deleted meanwhile: never inserted again") {
                    repository.upsert(read, envelope, expectedVersion = 4)
                }
                assertNull(repository.get(Sharing.orgA, id))
            }
        }

    private suspend fun TestServer.patchAs(token: String, path: String, request: UpdateAccountRequest) =
        client.patch(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    @Test
    fun anotherOrgNeitherSeesNorChangesNorUsesTheAccount() = serverTest(modules, overrides) { server ->
        with(server) {
            val account = shareAs("member-a").body<SharedAccountResponse>()
            assertEquals(emptyList(), client.get(base) { bearerAuth("admin-b") }.body<SharedAccountsResponse>().items)
            assertEquals(
                emptyList(),
                client.get("/api/v1/capacity") { bearerAuth("admin-b") }.body<CapacityResponse>().accounts,
            )
            assertEquals(HttpStatusCode.NotFound, client.delete("$base/${account.id}") { bearerAuth("admin-b") }.status)
            val path = "$base/${account.id}"
            assertEquals(HttpStatusCode.NotFound, patchAs("admin-b", path, UpdateAccountRequest(paused = true)).status)
            assertIs<LeaseResult.Refused>(
                koin.get<SharedAccountPool>().lease(
                    Sharing.orgB,
                    Sharing.adminB.memberId,
                    AccountProvider.CLAUDE,
                    null,
                ),
            )
            assertEquals(
                "ready",
                client.get(base) {
                    bearerAuth("admin-a")
                }.body<SharedAccountsResponse>().items.single().status,
            )
        }
    }

    @Test
    fun onlyCredentialsWithTheScopeReachTheAccounts() = serverTest(modules, overrides) { server ->
        with(server) {
            val account = shareAs("member-a").body<SharedAccountResponse>()
            val path = "$base/${account.id}"
            for (token in listOf("agent-a", "device-a")) {
                assertEquals(HttpStatusCode.Forbidden, client.get(base) { bearerAuth(token) }.status, token)
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/capacity") { bearerAuth(token) }.status)
                assertEquals(HttpStatusCode.Forbidden, shareAs(token, id = "other-id").status, token)
                assertEquals(HttpStatusCode.Forbidden, patchAs(token, path, UpdateAccountRequest(paused = true)).status)
                assertEquals(HttpStatusCode.Forbidden, client.delete(path) { bearerAuth(token) }.status, token)
            }
            assertEquals(HttpStatusCode.OK, client.get(base) { bearerAuth("reader-a") }.status, "accounts:read lists")
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/capacity") { bearerAuth("reader-a") }.status)
            assertEquals(
                HttpStatusCode.Forbidden,
                shareAs("reader-a", id = "other-id").status,
                "accounts:read does not write",
            )
            assertEquals(HttpStatusCode.Forbidden, client.delete(path) { bearerAuth("reader-a") }.status)
            assertEquals(1, client.get(base) { bearerAuth("admin-a") }.body<SharedAccountsResponse>().items.size)
        }
    }

    @Test
    fun rowLevelSecurityConfinesEveryQueryToItsOrg() = serverTest(modules, overrides) { server ->
        with(server) {
            val account = shareAs("member-a").body<SharedAccountResponse>()
            val leased = assertIs<LeaseResult.Leased>(
                koin.get<SharedAccountPool>().lease(
                    Sharing.orgA,
                    Sharing.memberA.memberId,
                    AccountProvider.CLAUDE,
                    null,
                ),
            ).lease
            leased.observe(200, mapOf("anthropic-ratelimit-unified-5h-utilization" to "0.5"))
            leased.release()
            for (table in listOf("sharing_accounts", "sharing_windows")) {
                val unfiltered = Statement.create("SELECT org_id FROM $table")
                assertEquals(1, driver.fetchInOrg(Sharing.orgA, unfiltered).getOrThrow().size, table)
                assertEquals(
                    0,
                    driver.fetchInOrg(Sharing.orgB, unfiltered).getOrThrow().size,
                    "No WHERE, no leak: $table",
                )
            }
            val stolen = Statement.create("UPDATE sharing_accounts SET label = 'TAKEN'")
            assertEquals(0L, driver.executeInOrg(Sharing.orgB, stolen).getOrThrow())
            assertEquals(
                0L,
                driver.executeInOrg(Sharing.orgB, Statement.create("DELETE FROM sharing_windows")).getOrThrow(),
            )
            val planted = Statement.create(
                "INSERT INTO sharing_windows (org_id, account_id, name, utilization, observed_at) " +
                    "VALUES (:org, :account, 'planted', 0, now())",
            ).bind("org", Sharing.orgA).bind("account", Uuid.parse(account.id))
            assertFails("Another org's row is refused") { driver.executeInOrg(Sharing.orgB, planted).getOrThrow() }
            val labels = driver.fetchInOrg(Sharing.orgA, Statement.create("SELECT label FROM sharing_accounts"))
                .getOrThrow().map { it.get("label").asString() }
            assertEquals(listOf("Lyes Max 20x"), labels, "Nothing of org A changed")
        }
    }

    @Test
    fun tokensAreRenewedRetiredAndCooledDownInTheDatabase() = serverTest(modules, refreshingProvider()) { server ->
        with(server) {
            val soon = (Clock.System.now() + 1.minutes).toString()
            val account = shareAs("member-a", expiresAt = soon).body<SharedAccountResponse>()
            val pool = koin.get<SharedAccountPool>()
            val leased = assertIs<LeaseResult.Leased>(
                pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
            ).lease
            assertEquals("renewed-access-token", leased.credentials.accessToken, "Renewed through the provider")
            leased.observe(429, mapOf("retry-after" to "120"))
            leased.release()
            val cooling = client.get(base) { bearerAuth("admin-a") }.body<SharedAccountsResponse>().items.single()
            assertNotEquals(null, cooling.cooldownUntil)
            assertIs<LeaseResult.Refused>(
                pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
            )

            val second = shareAs("member-a", id = "acct-uuid-2", label = "Second").body<SharedAccountResponse>()
            val renewed = assertIs<LeaseResult.Leased>(
                pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
            ).lease
            assertEquals(second.id, renewed.account.id.toString())
            renewed.observe(401, emptyMap())
            renewed.release()
            revoke = true
            assertIs<LeaseResult.Refused>(
                pool.lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
            )
            val statuses = client.get(base) { bearerAuth("admin-a") }.body<SharedAccountsResponse>().items
                .associate { it.id to it.status }
            assertEquals(mapOf(account.id to "ready", second.id to "reconnect_required"), statuses)
        }
    }

    /** One replica's tokens over the shared database: its own refresher, the server's repository and cipher. */
    private fun TestServer.replica(refresher: OAuthRefresher) = AccountTokens(
        koin.get<AccountRefreshRepository>(),
        koin.get<AccountTokenCipher>(),
        refresher,
        Clock.System,
        JsonLogger(LogLevel.ERROR, sink = {}),
    )

    private suspend fun TestServer.storedAccount(id: String): SharedAccount =
        koin.get<SharedAccountRepository>().get(Sharing.orgA, Uuid.parse(id))!!

    /** Moves the account's refresh intent [ago] into the past, by the database's clock: its owner died. */
    private suspend fun TestServer.ageIntent(id: String, seconds: Int) {
        driver.executeInOrg(
            Sharing.orgA,
            Statement.create(
                "UPDATE sharing_accounts SET refresh_started_at = now() - :seconds * INTERVAL '1 second' " +
                    "WHERE id = :id",
            ).bind("seconds", seconds).bind("id", Uuid.parse(id)),
        ).getOrThrow()
    }

    private suspend fun TestServer.intent(id: String): Pair<Boolean, Long> = driver.fetchInOrg(
        Sharing.orgA,
        Statement.create("SELECT refresh_pending, credential_version FROM sharing_accounts WHERE id = :id")
            .bind("id", Uuid.parse(id)),
    ).getOrThrow().single().let { it.get("refresh_pending").asBoolean() to it.get("credential_version").asLong() }

    @Test
    fun twoReplicasLeasingTogetherSendTheRefreshTokenOnceAndBothServeTheRenewedPair() =
        serverTest(modules, overrides) { server ->
            with(server) {
                val soon = (Clock.System.now() + 1.minutes).toString()
                val shared = shareAs("member-a", expiresAt = soon).body<SharedAccountResponse>()
                val account = storedAccount(shared.id)
                val renewed = AccountCredentials("renewed-access", "renewed-refresh", Clock.System.now() + 8.hours)
                val providers = List(2) {
                    FakeRefresher(RefreshResult.Renewed(renewed)).apply { onRefresh = { delay(500.milliseconds) } }
                }
                val replicas = providers.map { replica(it) }
                val served = coroutineScope {
                    replicas.map { tokens -> async { tokens.fresh(account) } }.awaitAll()
                }
                assertEquals(listOf("renewed-access", "renewed-access"), served.map { it?.accessToken })
                assertEquals(1, providers.sumOf { it.calls }, "One replica owns the refresh, the other waits for it")
                assertEquals(false to 2L, intent(shared.id), "The intent ended, the tokens' version moved once")
                assertEquals(AccountStatus.READY, storedAccount(shared.id).status)
            }
        }

    @Test
    fun aReplicaKilledDuringItsRefreshLeavesTheAccountToReconnectAndNoOtherReplicaSendsTheToken() =
        serverTest(modules, overrides) { server ->
            with(server) {
                val now = Clock.System.now().toString()
                val shared = shareAs("member-a", expiresAt = now).body<SharedAccountResponse>()
                val account = storedAccount(shared.id)
                val refreshes = koin.get<AccountRefreshRepository>()
                // The killed replica committed its intent, then died before the provider answered.
                refreshes.locked(Sharing.orgA, account.id) { RefreshWrite.Claim to Unit }
                val other = FakeRefresher(RefreshResult.Renewed(AccountCredentials("x", "y", Clock.System.now())))
                ageIntent(shared.id, seconds = 61)
                assertNull(replica(other).fresh(account), "A dead owner's refresh is never taken over")
                assertEquals(0, other.calls)
                assertEquals(AccountStatus.RECONNECT_REQUIRED, storedAccount(shared.id).status)
                assertEquals(true to 1L, intent(shared.id), "The intent stays until new tokens come")

                assertEquals(HttpStatusCode.Created, shareAs("member-a").status, "Its owner shares it again")
                assertEquals(false to 2L, intent(shared.id), "The share clears the intent")
                assertFalse(refreshes.save(Sharing.orgA, account.id, 1, "stale-owner"), "Its old version is fenced")
                assertFalse(refreshes.fail(Sharing.orgA, account.id, 1, AccountStatus.WRONG_ACCOUNT))
                assertEquals(AccountStatus.READY, storedAccount(shared.id).status)
                assertEquals("sk-ant-oat01-secret-access", replica(other).fresh(account)?.accessToken)
            }
        }

    @Test
    fun newTokensWaitForARefreshInFlightAndAFailedRefreshKeepsItsIntent() = serverTest(modules, overrides) { server ->
        with(server) {
            val shared = shareAs("member-a", expiresAt = Clock.System.now().toString()).body<SharedAccountResponse>()
            val account = storedAccount(shared.id)
            val refreshes = koin.get<AccountRefreshRepository>()
            refreshes.locked(Sharing.orgA, account.id) { RefreshWrite.Claim to Unit }
            val refused = shareAs("member-a")
            assertEquals(HttpStatusCode.Conflict, refused.status, "A share never overwrites a refresh in flight")
            assertTrue("being renewed" in refused.bodyAsText(), refused.bodyAsText())
            assertEquals(true to 1L, intent(shared.id))

            assertTrue(refreshes.fail(Sharing.orgA, account.id, 1, AccountStatus.RECONNECT_REQUIRED))
            assertEquals(true to 1L, intent(shared.id), "A failed refresh keeps its intent")
            assertEquals(HttpStatusCode.Created, shareAs("member-a").status, "An account to reconnect takes new tokens")
            assertEquals(false to 2L, intent(shared.id))

            // A row that is gone is never written, whatever the look decides.
            val missing = Uuid.random()
            assertEquals(null, refreshes.locked(Sharing.orgA, missing) { RefreshWrite.Claim to it })
            assertNull(koin.get<SharedAccountRepository>().get(Sharing.orgA, missing))
        }
    }

    @Test
    fun anOtherReplicaStillRefreshingIsWaitedForUntilItsIntentGoesStale() = serverTest(modules, overrides) { server ->
        with(server) {
            val shared = shareAs("member-a", expiresAt = Clock.System.now().toString()).body<SharedAccountResponse>()
            val account = storedAccount(shared.id)
            koin.get<AccountRefreshRepository>().locked(Sharing.orgA, account.id) { RefreshWrite.Claim to Unit }
            ageIntent(shared.id, seconds = 59)
            val waiting = FakeRefresher()
            val served = coroutineScope {
                val look = async { replica(waiting).fresh(account) }
                delay(1.seconds)
                ageIntent(shared.id, seconds = 61)
                look.await()
            }
            assertNull(served)
            assertEquals(0, waiting.calls)
            assertEquals(AccountStatus.RECONNECT_REQUIRED, storedAccount(shared.id).status)
        }
    }

    private var revoke = false

    /** The token endpoints answer a fresh token, or refuse the refresh token once [revoke] is set. */
    private fun refreshingProvider(): Module = module {
        includes(overrides)
        single(SharingModule.HTTP_CLIENT) {
            HttpClient(
                MockEngine {
                    if (revoke) {
                        respond("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest)
                    } else {
                        respond(
                            """{"access_token":"renewed-access-token","expires_in":28800}""",
                            HttpStatusCode.OK,
                            headersOf("Content-Type", "application/json"),
                        )
                    }
                },
            )
        }
    }

    @Test
    fun theModuleRenewsAtTheTokenEndpointTheInstanceSets() {
        val seen = mutableListOf<String>()
        val fakeProvider = module {
            includes(overrides)
            single<ConfigSource> {
                MapConfigSource(mapOf("OSTROM_SHARING_CLAUDE_TOKEN_URL" to "http://provider.localtest.me:8080/token"))
            }
            single(SharingModule.HTTP_CLIENT) {
                HttpClient(
                    MockEngine { request ->
                        seen += request.url.toString()
                        respond(
                            """{"access_token":"renewed","refresh_token":"next","expires_in":28800}""",
                            HttpStatusCode.OK,
                            headersOf("Content-Type", "application/json"),
                        )
                    },
                )
            }
        }
        serverTest(modules, fakeProvider) { server ->
            val credentials = AccountCredentials("old", "refresh", Clock.System.now())
            val result = server.koin.get<OAuthRefresher>().refresh(AccountProvider.CLAUDE, credentials)
            assertEquals("renewed", assertIs<RefreshResult.Renewed>(result).credentials.accessToken)
            assertEquals(listOf("http://provider.localtest.me:8080/token"), seen)
        }
    }

    @Test
    fun theModuleUsesTheOrgResolverAndTheSpendOfTheGateway() =
        serverTest(modules, module { includes(authenticator) }) { server ->
            with(server) {
                assertEquals(
                    HttpStatusCode.NotFound,
                    client.get(base) { bearerAuth("member-a") }.status,
                    "Without identity's resolver, nobody is a member of any org",
                )
            }
        }

    @Test
    fun aBoundSpendReaderStopsAnAccountOverItsBudget() = serverTest(
        modules,
        module {
            includes(overrides)
            single<AccountSpendReader> { AccountSpendReader { _, _ -> 9 } }
        },
    ) { server ->
        with(server) {
            val id = shareAs("member-a").body<SharedAccountResponse>().id
            patchAs(
                "admin-a",
                "$base/$id",
                UpdateAccountRequest(limits = AccountLimitsDto(monthlyBudgetMicros = 5).patch()),
            )
            val refusal = koin.get<SharedAccountPool>()
                .lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null)
            assertIs<LeaseResult.Refused>(refusal)
            val listed = client.get(base) { bearerAuth("admin-a") }.body<SharedAccountsResponse>().items.single()
            assertEquals(9, listed.spentMicros)
        }
    }

    @Test
    fun malformedRequestsAreRefused() = serverTest(modules, overrides) { server ->
        with(server) {
            val far = "2027-01-01T00:00:00Z"
            val requests = listOf(
                ShareAccountRequest("gemini", "x", null, "id", "a".repeat(10), "b".repeat(10), far),
                ShareAccountRequest("codex", "x", null, "id", "a".repeat(10), "b".repeat(10), "tomorrow"),
                ShareAccountRequest(
                    "codex",
                    "x",
                    null,
                    "id",
                    "a".repeat(10),
                    "b".repeat(10),
                    far,
                    AccountLimitsDto(allowedMembers = listOf("nope")),
                ),
            )
            assertFalse("aaaaaaaaaa" in requests.first().toString(), "Tokens never in logs")
            for (request in requests) {
                val refused = client.post(base) {
                    bearerAuth("member-a")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                assertEquals(HttpStatusCode.BadRequest, refused.status, request.toString())
            }
            val restricted = client.post(base) {
                bearerAuth("member-a")
                contentType(ContentType.Application.Json)
                setBody(
                    ShareAccountRequest(
                        "codex",
                        "Codex",
                        null,
                        "id",
                        "a".repeat(10),
                        "b".repeat(10),
                        far,
                        AccountLimitsDto(allowedMembers = listOf(Sharing.otherA.memberId.toString())),
                    ),
                )
            }.body<SharedAccountResponse>()
            assertEquals(listOf(Sharing.otherA.memberId.toString()), restricted.sharedWith)
            assertEquals(listOf(Sharing.otherA.memberId.toString()), restricted.limits.allowedMembers)
            assertEquals(HttpStatusCode.Unauthorized, client.get(base).status)
        }
    }

    @Test
    fun anOwnerlessAccountShowsNoOwnerAndMissingRowsReadAsNothing() = serverTest(modules, overrides) { server ->
        with(server) {
            val account = shareAs("member-a").body<SharedAccountResponse>()
            val id = Uuid.parse(account.id)
            val orphaned = Statement.create("UPDATE sharing_accounts SET owner_member_id = NULL WHERE id = :id")
                .bind("id", id)
            assertEquals(1L, driver.executeInOrg(Sharing.orgA, orphaned).getOrThrow())
            val seen = client.get(base) { bearerAuth("admin-a") }.body<SharedAccountsResponse>().items.single()
            assertEquals("", seen.owner)
            assertFalse(seen.mine)

            val repository = koin.get<SharedAccountRepository>()
            val stored = assertNotNull(repository.get(Sharing.orgA, id))
            assertNull(repository.update(stored.copy(id = Uuid.random())), "A row deleted meanwhile updates nothing")
            assertNull(repository.envelope(Sharing.orgA, Uuid.random()))
        }
    }
}
