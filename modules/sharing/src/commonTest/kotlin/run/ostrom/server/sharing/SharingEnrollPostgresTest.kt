// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing

import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.ORG_HEADER
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.CreateSecretPayload
import run.ostrom.server.secrets.domain.CreateSecretUseCase
import run.ostrom.server.secrets.domain.MemberRole
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.domain.SecretValue
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountSpendReader
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The console's enrollment, the contract's `enrollSharedAccount` body sent as is (`provider`, `label`,
 * `credentialSecret`, `sharedWith` by login, `dailyAllocationUsd`), against the real identity, orgs, secrets and
 * sharing modules on PostgreSQL, every request as the serving role under row-level security: the token is read from
 * the named secret on the server, the people are named by login both ways, the daily share stops the account, and
 * another organization sees nothing.
 */
class SharingEnrollPostgresTest {
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** Tokens of the test: the token is the person's login, resolved to their user id. */
    private val people = mutableMapOf<String, Uuid>()

    /** What every account served, in USD millionths. */
    private var spent = 0L

    private val overrides = module {
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<AccountSpendReader> { AccountSpendReader { _, _ -> spent } }
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
    private val base = "/api/v1/accounts"

    /** A token from a secret: what `claude setup-token` prints, pasted with its line break. */
    private val token = "sk-ant-oat01-enroll-${Uuid.random()}"

    private suspend fun TestServer.person(login: String): User {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        people[login] = user.id
        return user
    }

    /** An organization owned by [owner]; returns its id and the owner's member id. */
    private suspend fun TestServer.organization(slug: String, owner: User): Pair<Uuid, Uuid> {
        val org = Org(Uuid.random(), slug, slug.uppercase(), null, null, now)
        val member = Member(Uuid.random(), org.id, owner.id, OrgRole.OWNER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        return org.id to member.id
    }

    private suspend fun TestServer.join(orgId: Uuid, user: User): Uuid {
        val id = Uuid.random()
        driver.withOrganization(orgId) {
            it.execute(
                Statement.create(
                    "INSERT INTO orgs_members (id, org_id, user_id, role, joined_at) " +
                        "VALUES (:id, :org, :user, 'member', :at)",
                ).bind("id", id).bind("org", orgId).bind("user", user.id).bind("at", now),
            ).getOrThrow()
        }
        return id
    }

    private suspend fun TestServer.secret(caller: Caller, name: String, personal: Boolean, value: String) {
        koin.get<CreateSecretUseCase>()(caller, CreateSecretPayload(name, SecretValue(value), personal))
    }

    private suspend fun TestServer.send(
        method: String,
        login: String,
        path: String,
        body: JsonObject? = null,
        org: Uuid? = null,
    ): HttpResponse {
        val build: HttpRequestBuilder.() -> Unit = {
            bearerAuth(login)
            if (org != null) header(ORG_HEADER, org.toString())
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        return when (method) {
            "POST" -> client.post(path, build)
            "PATCH" -> client.patch(path, build)
            "DELETE" -> client.delete(path, build)
            else -> client.get(path, build)
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** The contract's body, as the console's form sends it. */
    private fun enrollment(
        secret: String = "CLAUDE_MAX_TOKEN",
        sharedWith: List<String> = listOf("liam"),
        provider: String = "anthropic",
        daily: Double? = 25.0,
    ) = buildJsonObject {
        put("provider", provider)
        put("label", "Kira’s Claude Max")
        put("credentialSecret", secret)
        put("sharedWith", buildJsonArray { sharedWith.forEach { add(JsonPrimitive(it)) } })
        if (daily != null) put("dailyAllocationUsd", daily)
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.logins(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun theConsoleLendsASubscriptionFromASecretToAMemberByLogin() = serverTest(modules, overrides) { server ->
        with(server) {
            val kira = person("kira")
            val liam = person("liam")
            val (org, kiraMember) = organization("kira-co", kira)
            val liamMember = join(org, liam)
            secret(Caller(org, kiraMember, MemberRole.OWNER), "CLAUDE_MAX_TOKEN", false, "$token\n")

            val made = send("POST", "kira", base, enrollment())
            assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
            assertFalse(token in made.bodyAsText(), "The token never comes back")
            val account = made.json()
            assertEquals("claude", account.text("provider"), "anthropic names the Claude subscription")
            assertEquals("Kira’s Claude Max", account.text("label"))
            assertEquals("kira", account.text("owner"))
            assertEquals(listOf("liam"), account.logins("sharedWith"))
            assertEquals(25.0, account.getValue("dailyAllocationUsd").jsonPrimitive.double)
            assertEquals("false", account.text("paused"))
            assertEquals("ready", account.text("status"))
            val id = account.text("id")

            val listed = send("GET", "kira", base)
            assertFalse(token in listed.bodyAsText())
            val items = listed.json().getValue("items").jsonArray.map { it.jsonObject }
            assertEquals(listOf(id), items.map { it.text("id") })
            assertEquals(listOf("liam"), items.single().logins("sharedWith"))
            assertEquals("kira", items.single().text("owner"))
            val stored = driver.fetchInOrg(org, Statement.create("SELECT envelope FROM sharing_accounts"))
                .getOrThrow().single().get("envelope").asString()
            assertFalse(token in stored, "Sealed at rest")

            leases(org, liamMember, kiraMember)

            val path = "$base/$id"
            patches(path)

            // Liam sees a neutral account: neither its owner, nor to whom it is lent, nor its daily share.
            val seen = send("GET", "liam", base).json().getValue("items").jsonArray.single().jsonObject
            assertEquals("", seen.text("owner"))
            assertEquals(listOf(), seen.logins("sharedWith"))
            assertFalse("dailyAllocationUsd" in seen)

            assertEquals(HttpStatusCode.NoContent, send("DELETE", "kira", path).status)
            assertEquals(listOf(), send("GET", "kira", base).json().getValue("items").jsonArray.toList())
        }
    }

    @Test
    fun aMemberEnrollsFromTheirOwnSecretOnlyAndAnotherOrganizationSeesNothing() =
        serverTest(modules, overrides) { server ->
            with(server) {
                val kira = person("kira")
                val liam = person("liam")
                val milo = person("milo")
                val (org, kiraMember) = organization("kira-co", kira)
                val liamMember = join(org, liam)
                val (other, miloMember) = organization("milo-co", milo)
                secret(Caller(org, kiraMember, MemberRole.OWNER), "CLAUDE_MAX_TOKEN", false, token)
                secret(Caller(org, liamMember, MemberRole.MEMBER), "LIAM_CODEX", true, "$token-codex")
                secret(Caller(other, miloMember, MemberRole.OWNER), "MILO_ONLY", false, "$token-milo")

                // The organization's secret is its admins' to lend; a member lends a secret of their own.
                val orgSecret = send("POST", "liam", base, enrollment(sharedWith = emptyList()))
                assertEquals(HttpStatusCode.NotFound, orgSecret.status, orgSecret.bodyAsText())
                val own = send("POST", "liam", base, enrollment("LIAM_CODEX", emptyList(), "openai", null))
                assertEquals(HttpStatusCode.Created, own.status, own.bodyAsText())
                val codex = own.json()
                assertEquals("codex", codex.text("provider"))
                assertEquals("liam", codex.text("owner"))
                assertEquals(listOf(), codex.logins("sharedWith"), "Empty: the whole organization")
                assertFalse("dailyAllocationUsd" in codex, "No daily share")

                // Nobody of another organization, no secret of another organization, nothing malformed.
                val refusals = listOf(
                    enrollment(sharedWith = listOf("milo")) to HttpStatusCode.BadRequest,
                    enrollment("MILO_ONLY") to HttpStatusCode.NotFound,
                    enrollment("NO_SUCH") to HttpStatusCode.NotFound,
                    enrollment(provider = "gemini") to HttpStatusCode.BadRequest,
                    enrollment(daily = -1.0) to HttpStatusCode.BadRequest,
                    enrollment(daily = 0.0) to HttpStatusCode.BadRequest,
                )
                for ((body, status) in refusals) {
                    assertEquals(status, send("POST", "kira", base, body).status, body.toString())
                }
                val wrongType = buildJsonObject {
                    put("provider", "claude")
                    put("label", "x")
                    put("credentialSecret", buildJsonObject { put("name", "CLAUDE_MAX_TOKEN") })
                }
                assertEquals(HttpStatusCode.BadRequest, send("POST", "kira", base, wrongType).status)
                val neither = buildJsonObject {
                    put("provider", "claude")
                    put("label", "x")
                }
                assertEquals(HttpStatusCode.BadRequest, send("POST", "kira", base, neither).status)

                sharesTokens()

                // Milo names Kira's organization: nothing, not even its accounts' existence.
                val id = codex.text("id")
                assertEquals(HttpStatusCode.NotFound, send("GET", "milo", base, org = org).status)
                assertEquals(HttpStatusCode.NotFound, send("DELETE", "milo", "$base/$id", org = org).status)
                assertEquals(listOf(), send("GET", "milo", base).json().getValue("items").jsonArray.toList())
                assertTrue(send("GET", "kira", base).bodyAsText().contains(id))
            }
        }

    /** Lent to liam alone, with the token of the secret, line break trimmed; its daily share spent, it stops. */
    private suspend fun TestServer.leases(org: Uuid, liamMember: Uuid, kiraMember: Uuid) {
        val pool = koin.get<SharedAccountPool>()
        val lease = assertIs<LeaseResult.Leased>(pool.lease(org, liamMember, AccountProvider.CLAUDE, null)).lease
        assertEquals(token, lease.credentials.accessToken)
        lease.release()
        assertIs<LeaseResult.Refused>(pool.lease(org, kiraMember, AccountProvider.CLAUDE, null))

        // Its daily share spent, it stops until the next UTC day.
        spent = 25_000_000
        val refused = assertIs<LeaseResult.Refused>(pool.lease(org, liamMember, AccountProvider.CLAUDE, null))
        val tomorrow = Instant.fromEpochSeconds((Clock.System.now().epochSeconds / DAY + 1) * DAY)
        assertEquals(tomorrow, refused.refusal.retryAt)
        spent = 0
    }

    /** Paused and resumed, lent to its owner by login, its daily share changed: the contract's PATCH. */
    private suspend fun TestServer.patches(path: String) {
        val paused = send("PATCH", "kira", path, buildJsonObject { put("paused", true) })
        assertEquals("true", paused.json().text("paused"))
        val resumed = send("PATCH", "kira", path, buildJsonObject { put("paused", false) })
        assertEquals("false", resumed.json().text("paused"))
        val moved = send(
            "PATCH",
            "kira",
            path,
            buildJsonObject {
                put("sharedWith", JsonArray(listOf(JsonPrimitive("KIRA"))))
                put("dailyAllocationUsd", 10.5)
            },
        ).json()
        assertEquals(listOf("kira"), moved.logins("sharedWith"))
        assertEquals(10.5, moved.getValue("dailyAllocationUsd").jsonPrimitive.double)
        val both = send(
            "PATCH",
            "kira",
            path,
            buildJsonObject {
                put("limits", buildJsonObject { put("maxConcurrent", 2) })
                put("dailyAllocationUsd", 1)
            },
        ).json()
        assertEquals(1.0, both.getValue("dailyAllocationUsd").jsonPrimitive.double)
        assertEquals("2", both.getValue("limits").jsonObject.text("maxConcurrent"))
        val tooMuch = send("PATCH", "kira", path, buildJsonObject { put("dailyAllocationUsd", 2_000_000) })
        assertEquals(HttpStatusCode.BadRequest, tooMuch.status)
    }

    /** A null credentialSecret is the tokens' body, which always carries a refresh token. */
    private suspend fun TestServer.sharesTokens() {
        val tokens = buildJsonObject {
            put("provider", "claude")
            put("label", "Liam Max")
            put("credentialSecret", JsonNull)
            put("providerAccountId", "acct-liam")
            put("accessToken", "a".repeat(10))
            put("refreshToken", "b".repeat(10))
            put("expiresAt", "2099-01-01T00:00:00Z")
        }
        val noRefresh = JsonObject(tokens + ("refreshToken" to JsonPrimitive("")))
        assertEquals(HttpStatusCode.BadRequest, send("POST", "liam", base, noRefresh).status)
        assertEquals(HttpStatusCode.Created, send("POST", "liam", base, tokens).status)
    }

    private companion object {
        const val DAY = 86_400L
    }
}
