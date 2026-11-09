// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing

import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.User
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
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
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.request.HttpRequestBuilder
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * An account lent to groups (`sharedWith: group:<id>`) against the real identity, orgs, secrets and sharing modules on
 * PostgreSQL, every request as the serving role under row-level security: the account serves the group's members who
 * are not paused, the account's `sharedWith` and the group's `accounts` are the same link both ways, and a group of
 * another organization lends nothing.
 */
class SharingGroupsLendingPostgresTest {
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** Tokens of the test: the token is the person's login, resolved to their user id. */
    private val people = mutableMapOf<String, Uuid>()

    private val overrides = module {
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
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
    private val accounts = "/api/v1/accounts"
    private val groups = "/api/v1/groups"

    /** Kira's organization: liam and mia, the group Backend of liam, and the ids of their members. */
    private class Team(val org: Uuid, val kira: Uuid, val liam: Uuid, val mia: Uuid, val backend: String)

    private suspend fun TestServer.person(login: String): User {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        people[login] = user.id
        return user
    }

    /** An organization owned by [login], with its secret `TOKEN`; returns its id and the owner's member id. */
    private suspend fun TestServer.organization(slug: String, login: String): Pair<Uuid, Uuid> {
        val org = Org(Uuid.random(), slug, slug.uppercase(), null, null, now)
        val member = Member(Uuid.random(), org.id, person(login).id, OrgRole.OWNER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        koin.get<CreateSecretUseCase>()(
            Caller(org.id, member.id, MemberRole.OWNER),
            CreateSecretPayload("TOKEN", SecretValue("sk-ant-oat01-$slug"), false),
        )
        return org.id to member.id
    }

    private suspend fun TestServer.join(orgId: Uuid, login: String): Uuid {
        val id = Uuid.random()
        val user = person(login)
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

    private suspend fun TestServer.team(): Team {
        val (org, kira) = organization("kira-co", "kira")
        val liam = join(org, "liam")
        val mia = join(org, "mia")
        val backend = buildJsonObject {
            put("name", "Backend")
            put("members", list("liam"))
        }
        val made = send("POST", "kira", groups, backend)
        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        return Team(org, kira, liam, mia, made.json().text("id"))
    }

    private suspend fun TestServer.send(method: String, login: String, path: String, body: JsonObject? = null) =
        when (method) {
            "POST" -> client.post(path, request(login, body))
            "PATCH" -> client.patch(path, request(login, body))
            "DELETE" -> client.delete(path, request(login, body))
            else -> client.get(path, request(login, body))
        }

    private fun request(login: String, body: JsonObject?): HttpRequestBuilder.() -> Unit = {
        bearerAuth(login)
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun list(vararg values: String) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun enrollment(vararg sharedWith: String) = buildJsonObject {
        put("provider", "anthropic")
        put("label", "Team Max")
        put("credentialSecret", "TOKEN")
        put("sharedWith", list(*sharedWith))
    }

    private fun sharedWith(vararg values: String) = buildJsonObject { put("sharedWith", list(*values)) }

    private suspend fun TestServer.enroll(login: String, vararg sharedWith: String): JsonObject {
        val made = send("POST", login, accounts, enrollment(*sharedWith))
        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        return made.json()
    }

    /** Whether [member] of [org] gets a Claude account from the pool now. */
    private suspend fun TestServer.serves(org: Uuid, member: Uuid): Boolean {
        val result = koin.get<SharedAccountPool>().lease(org, member, AccountProvider.CLAUDE, null)
        if (result is LeaseResult.Leased) result.lease.release()
        return result is LeaseResult.Leased
    }

    @Test
    fun anAccountLentToAGroupServesItsMembersWhoAreNotPaused() = serverTest(modules, overrides) { server ->
        with(server) {
            val team = team()
            val group = "group:${team.backend}"
            val account = enroll("kira", group)
            assertEquals(listOf(group), account.strings("sharedWith"))
            val id = account.text("id")
            assertEquals(listOf(id), send("GET", "kira", "$groups/${team.backend}").json().strings("accounts"))

            assertEquals(true, serves(team.org, team.liam), "A member of the group")
            assertEquals(false, serves(team.org, team.mia), "Not in the group")
            assertEquals(false, serves(team.org, team.kira), "Only the group: not the whole organization")

            val pause = buildJsonObject { put("until", "2099-01-01T00:00:00Z") }
            val paused = send("POST", "kira", "$groups/${team.backend}/members/liam/pause", pause)
            assertEquals(HttpStatusCode.NoContent, paused.status, paused.bodyAsText())
            assertEquals(false, serves(team.org, team.liam), "Paused in the group")
            val over = buildJsonObject { put("until", "2000-01-01T00:00:00Z") }
            send("POST", "kira", "$groups/${team.backend}/members/liam/pause", over)
            assertEquals(true, serves(team.org, team.liam), "A pause that is over")

            val both = send("PATCH", "kira", "$accounts/$id", sharedWith("mia", group))
            assertEquals(listOf(group, "mia").sorted(), both.json().strings("sharedWith"))
            assertEquals(true, serves(team.org, team.mia), "Named directly")

            assertEquals(HttpStatusCode.NoContent, send("DELETE", "kira", "$groups/${team.backend}").status)
            val listed = send("GET", "kira", accounts).json().getValue("items").jsonArray.single().jsonObject
            assertEquals(listOf("mia"), listed.strings("sharedWith"), "A deleted group lends nothing")
            assertEquals(false, serves(team.org, team.liam))
        }
    }

    @Test
    fun theAccountsSharedWithAndTheGroupsAccountsAreOneLink() = serverTest(modules, overrides) { server ->
        with(server) {
            val team = team()
            val group = "group:${team.backend}"
            val id = enroll("kira", "mia").text("id")
            val lent = send("PATCH", "kira", "$groups/${team.backend}", buildJsonObject { put("accounts", list(id)) })
            assertEquals(listOf(id), lent.json().strings("accounts"), lent.bodyAsText())
            val listed = send("GET", "kira", accounts).json().getValue("items").jsonArray.single().jsonObject
            assertEquals(listOf(group, "mia").sorted(), listed.strings("sharedWith"))
            assertEquals(true, serves(team.org, team.liam), "Lent from the group's side")

            val open = send("PATCH", "kira", "$accounts/$id", sharedWith())
            assertEquals(emptyList(), open.json().strings("sharedWith"), "The whole organization")
            assertEquals(emptyList(), send("GET", "kira", "$groups/${team.backend}").json().strings("accounts"))
            assertEquals(true, serves(team.org, team.kira))

            // A member never sees to whom an account is lent, groups included.
            send("PATCH", "kira", "$accounts/$id", sharedWith(group))
            val seen = send("GET", "liam", accounts).json().getValue("items").jsonArray.single().jsonObject
            assertEquals(emptyList(), seen.strings("sharedWith"))
        }
    }

    @Test
    fun aGroupOfAnotherOrganizationOrAMalformedOneLendsNothing() = serverTest(modules, overrides) { server ->
        with(server) {
            val team = team()
            val (bobOrg, _) = organization("bob-co", "bob")
            val theirs = send("POST", "bob", groups, buildJsonObject { put("name", "Theirs") }).json().text("id")
            val id = enroll("kira").text("id")

            for (value in listOf("group:$theirs", "group:theirs", "group:${Uuid.random()}")) {
                val refused = send("PATCH", "kira", "$accounts/$id", sharedWith(value))
                assertEquals(HttpStatusCode.BadRequest, refused.status, value)
                assertEquals(HttpStatusCode.BadRequest, send("POST", "kira", accounts, enrollment(value)).status)
            }
            assertEquals(emptyList(), send("GET", "bob", "$groups/$theirs").json().strings("accounts"))
            assertEquals(true, serves(team.org, team.liam), "Still the whole organization")
            assertFalse(id in send("GET", "bob", accounts).bodyAsText())
            assertEquals(false, serves(bobOrg, team.liam))
            val foreign = send("PATCH", "bob", "$accounts/$id", sharedWith("group:$theirs"))
            assertEquals(HttpStatusCode.NotFound, foreign.status)
        }
    }
}
