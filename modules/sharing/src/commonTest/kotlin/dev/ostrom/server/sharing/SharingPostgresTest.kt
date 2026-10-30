package dev.ostrom.server.sharing

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.Caller
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.LeaseResult
import dev.ostrom.server.sharing.domain.SharedAccountPool
import dev.ostrom.server.sharing.presentation.AccountLimitsDto
import dev.ostrom.server.sharing.presentation.CapacityPoolDto
import dev.ostrom.server.sharing.presentation.CapacityResponse
import dev.ostrom.server.sharing.presentation.ShareAccountRequest
import dev.ostrom.server.sharing.presentation.SharedAccountResponse
import dev.ostrom.server.sharing.presentation.SharedAccountsResponse
import dev.ostrom.server.sharing.presentation.UpdateAccountRequest
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
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
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** Real PostgreSQL and HTTP: sharing, tokens sealed at rest and never returned, neutral labels, org isolation. */
class SharingPostgresTest {
    private val byToken = mapOf("admin-a" to Sharing.adminA, "member-a" to Sharing.memberA, "other-a" to Sharing.otherA, "admin-b" to Sharing.adminB)

    private val overrides = module {
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = byToken[token]?.let { AuthenticatedPrincipal(token, PrincipalKind.USER, emptySet()) }
            }
        }
        single<CallerResolver> {
            object : CallerResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): Caller? =
                    byToken[principal.subjectId]?.takeIf { orgId == null || it.orgId == orgId }
            }
        }
    }

    private val base = "/api/v1/accounts"

    private suspend fun TestServer.shareAs(token: String, id: String = "acct-uuid-1", label: String = "Lyes Max 20x"): HttpResponse =
        client.post(base) {
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
                    expiresAt = (Clock.System.now() + 8.hours).toString(),
                    limits = AccountLimitsDto(maxConcurrent = 4),
                ),
            )
        }

    @Test
    fun aSharedAccountIsSealedAtRestAndLeasedByThePool() = serverTest(listOf(SecretsModule, SharingModule), overrides) { server ->
        with(server) {
            val created = shareAs("member-a")
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            assertFalse("secret" in created.bodyAsText(), "Tokens never come back")
            val account = created.body<SharedAccountResponse>()
            assertTrue(account.mine)
            val stored = driver.fetchAll("SELECT envelope FROM sharing_accounts").getOrThrow().single().get("envelope").asString()
            assertFalse("secret" in stored, "Sealed at rest")

            val lease = koin.get<SharedAccountPool>().lease(Sharing.orgA, Sharing.otherA.memberId, AccountProvider.CLAUDE, "session-1")
            val leased = assertIs<LeaseResult.Leased>(lease).lease
            assertEquals("sk-ant-oat01-secret-access", leased.credentials.accessToken)
            leased.observe(200, mapOf("anthropic-ratelimit-unified-5h-utilization" to "0.25"))
            leased.release()

            val seenByOther = client.get(base) { bearerAuth("other-a") }.body<SharedAccountsResponse>().items.single()
            assertEquals("Account 1", seenByOther.label)
            assertEquals(null, seenByOther.email)
            assertEquals(null, seenByOther.providerAccountId)
            assertEquals("", seenByOther.owner)
            assertEquals(listOf("five_hour"), seenByOther.windows.map { it.window })
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
    fun ownersAndAdminsChangeAndRemoveOthersCannot() = serverTest(listOf(SecretsModule, SharingModule), overrides) { server ->
        with(server) {
            val account = shareAs("member-a").body<SharedAccountResponse>()
            val path = "$base/${account.id}"
            val byOther = client.patch(path) {
                bearerAuth("other-a")
                contentType(ContentType.Application.Json)
                setBody(UpdateAccountRequest(paused = true))
            }
            assertEquals(HttpStatusCode.NotFound, byOther.status)
            assertEquals(HttpStatusCode.Conflict, shareAs("other-a").status, "Another member cannot take it over")
            val off = client.patch(path) {
                bearerAuth("member-a")
                contentType(ContentType.Application.Json)
                setBody(UpdateAccountRequest(paused = true, priority = 5))
            }.body<SharedAccountResponse>()
            assertEquals("disabled", off.status)
            assertTrue(off.paused)
            val restricted = client.patch(path) {
                bearerAuth("member-a")
                contentType(ContentType.Application.Json)
                setBody(UpdateAccountRequest(sharedWith = listOf(Sharing.otherA.memberId.toString())))
            }.body<SharedAccountResponse>()
            assertEquals(listOf(Sharing.otherA.memberId.toString()), restricted.sharedWith)
            assertEquals(4, restricted.limits.maxConcurrent, "Other limits kept")
            val badMembers = client.patch(path) {
                bearerAuth("member-a")
                contentType(ContentType.Application.Json)
                setBody(UpdateAccountRequest(sharedWith = listOf("nope")))
            }
            assertEquals(HttpStatusCode.BadRequest, badMembers.status)
            val open = client.patch(path) {
                bearerAuth("admin-a")
                contentType(ContentType.Application.Json)
                setBody(UpdateAccountRequest(sharedWith = emptyList(), limits = AccountLimitsDto(maxConcurrent = 2)))
            }.body<SharedAccountResponse>()
            assertEquals(emptyList(), open.sharedWith)
            assertEquals(2, open.limits.maxConcurrent)
            val notVisible = client.patch(path) {
                bearerAuth("other-a")
                contentType(ContentType.Application.Json)
                setBody(UpdateAccountRequest(sharedWith = emptyList()))
            }
            assertEquals(HttpStatusCode.NotFound, notVisible.status)
            assertIs<LeaseResult.Refused>(koin.get<SharedAccountPool>().lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null))
            assertEquals(HttpStatusCode.NoContent, client.delete(path) { bearerAuth("admin-a") }.status)
            assertEquals(HttpStatusCode.NotFound, client.delete(path) { bearerAuth("admin-a") }.status)
            assertEquals(HttpStatusCode.NotFound, client.delete("$base/not-an-id") { bearerAuth("admin-a") }.status)
        }
    }

    @Test
    fun anotherOrgNeitherSeesNorUsesTheAccount() = serverTest(listOf(SecretsModule, SharingModule), overrides) { server ->
        with(server) {
            val account = shareAs("member-a").body<SharedAccountResponse>()
            assertEquals(emptyList(), client.get(base) { bearerAuth("admin-b") }.body<SharedAccountsResponse>().items)
            assertEquals(emptyList(), client.get("/api/v1/capacity") { bearerAuth("admin-b") }.body<CapacityResponse>().accounts)
            assertEquals(HttpStatusCode.NotFound, client.delete("$base/${account.id}") { bearerAuth("admin-b") }.status)
            assertIs<LeaseResult.Refused>(koin.get<SharedAccountPool>().lease(Sharing.orgB, Sharing.adminB.memberId, AccountProvider.CLAUDE, null))
        }
    }

    @Test
    fun malformedRequestsAreRefused() = serverTest(listOf(SecretsModule, SharingModule), overrides) { server ->
        with(server) {
            val badProvider = client.post(base) {
                bearerAuth("member-a")
                contentType(ContentType.Application.Json)
                setBody(ShareAccountRequest("gemini", "x", null, "id", "a".repeat(10), "b".repeat(10), "2027-01-01T00:00:00Z"))
            }
            assertEquals(HttpStatusCode.BadRequest, badProvider.status)
            val badDate = client.post(base) {
                bearerAuth("member-a")
                contentType(ContentType.Application.Json)
                setBody(ShareAccountRequest("codex", "x", null, "id", "a".repeat(10), "b".repeat(10), "tomorrow"))
            }
            assertEquals(HttpStatusCode.BadRequest, badDate.status)
            val badMembers = client.post(base) {
                bearerAuth("member-a")
                contentType(ContentType.Application.Json)
                setBody(ShareAccountRequest("codex", "x", null, "id", "a".repeat(10), "b".repeat(10), "2027-01-01T00:00:00Z", AccountLimitsDto(allowedMembers = listOf("nope"))))
            }
            assertEquals(HttpStatusCode.BadRequest, badMembers.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get(base).status)
        }
    }
}
