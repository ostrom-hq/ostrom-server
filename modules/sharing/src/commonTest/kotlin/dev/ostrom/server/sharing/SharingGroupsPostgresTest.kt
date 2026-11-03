// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing

import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.ORG_HEADER
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.sharing.domain.AccountLimits
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.AccountStatus
import dev.ostrom.server.sharing.domain.SharedAccount
import dev.ostrom.server.sharing.domain.SharedAccountRepository
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The groups of `/api/v1/groups` against the real identity, orgs, secrets and sharing modules on PostgreSQL, every
 * request as the serving role under row-level security: owners and admins write, every member reads, people are named
 * by login, an account is lent only by its own organization, and another organization sees and changes nothing.
 */
class SharingGroupsPostgresTest {
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
    private val base = "/api/v1/groups"
    private val later = "2099-01-01T00:00:00Z"

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

    private suspend fun TestServer.join(orgId: Uuid, user: User, role: String = "member"): Uuid {
        val id = Uuid.random()
        driver.withOrganization(orgId) {
            it.execute(
                Statement.create(
                    "INSERT INTO orgs_members (id, org_id, user_id, role, joined_at) " +
                        "VALUES (:id, :org, :user, :role, :at)",
                ).bind("id", id).bind("org", orgId).bind("user", user.id).bind("role", role).bind("at", now),
            ).getOrThrow()
        }
        return id
    }

    /** A shared account of [orgId], straight in the repository: its tokens play no part here. */
    private suspend fun TestServer.account(orgId: Uuid, owner: Uuid, name: String): String {
        val account = SharedAccount(
            Uuid.random(), orgId, AccountProvider.CLAUDE, name, null, "acct-$name", owner, 0,
            AccountStatus.READY, AccountLimits(), null, now,
        )
        return koin.get<SharedAccountRepository>().upsert(account, "sealed", null).id.toString()
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
            "POST" -> client.post(path, build)
            "PATCH" -> client.patch(path, build)
            "PUT" -> client.put(path, build)
            "DELETE" -> client.delete(path, build)
            else -> client.get(path, build)
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun group(name: String, members: List<String> = emptyList(), accounts: List<String> = emptyList()) =
        buildJsonObject {
            put("name", name)
            put("members", strings(members))
            put("accounts", strings(accounts))
        }

    private fun pause(until: String) = buildJsonObject { put("until", until) }

    private fun rename(name: String) = buildJsonObject { put("name", name) }

    private fun assertStatus(expected: HttpStatusCode, response: HttpResponse, what: String) =
        assertEquals(expected, response.status, what)

    /** Kira's organization with liam, mia, an admin ana and the account max; the group Backend lends max to liam. */
    private suspend fun TestServer.team(): Pair<String, JsonObject> {
        val kira = person("kira")
        val (org, kiraMember) = organization("kira-co", kira)
        join(org, person("liam"))
        join(org, person("mia"))
        join(org, person("ana"), role = "admin")
        val max = account(org, kiraMember, "max")
        val made = send("POST", "kira", base, group(" Backend ", listOf("liam", "LIAM"), listOf(max)))
        assertStatus(HttpStatusCode.Created, made, made.bodyAsText())
        return max to made.json()
    }

    @Test
    fun anOwnerMakesAGroupEveryMemberReadsItAndNoMemberChangesIt() = serverTest(modules, overrides) { server ->
        with(server) {
            val (max, backend) = team()
            val id = backend.text("id")
            assertEquals("Backend", backend.text("name"), "The name is trimmed")
            assertEquals(listOf("liam"), backend.strings("members"), "A login in any case names one member")
            assertEquals(listOf(max), backend.strings("accounts"))
            assertEquals(JsonArray(emptyList()), backend.getValue("paused"))

            val listed = send("GET", "mia", base).json().getValue("items").jsonArray
            assertEquals(listOf(id), listed.map { it.jsonObject.text("id") }, "A member reads the groups")
            assertEquals(backend, send("GET", "liam", "$base/$id").json(), "A member reads one group")

            // A member reads only: every write is refused before anything is read.
            assertStatus(HttpStatusCode.Forbidden, send("POST", "mia", base, group("Mine")), "member creates")
            assertStatus(HttpStatusCode.Forbidden, send("PATCH", "mia", "$base/$id", rename("X")), "member renames")
            assertStatus(HttpStatusCode.Forbidden, send("DELETE", "mia", "$base/$id"), "member deletes")
            assertStatus(HttpStatusCode.Forbidden, send("PUT", "mia", "$base/$id/members/mia"), "member joins")
            assertStatus(HttpStatusCode.Forbidden, send("DELETE", "mia", "$base/$id/members/liam"), "member removes")
            assertStatus(
                HttpStatusCode.Forbidden,
                send("POST", "mia", "$base/$id/members/liam/pause", pause(later)),
                "member pauses",
            )
        }
    }

    @Test
    fun anAdminRenamesAndLendsUnderANameUniqueInAnyCase() = serverTest(modules, overrides) { server ->
        with(server) {
            val (max, backend) = team()
            val id = backend.text("id")
            val mobile = send("POST", "ana", base, group("Mobile"))
            assertStatus(HttpStatusCode.Created, mobile, mobile.bodyAsText())
            val mobileId = mobile.json().text("id")
            assertStatus(HttpStatusCode.Conflict, send("POST", "kira", base, group("backend")), "a name in any case")
            assertStatus(
                HttpStatusCode.Conflict,
                send("PATCH", "kira", "$base/$id", rename("MOBILE")),
                "a rename onto another group's name",
            )

            val renamed = send(
                "PATCH",
                "ana",
                "$base/$id",
                buildJsonObject {
                    put("name", "Platform")
                    put("accounts", strings(emptyList()))
                },
            )
            assertStatus(HttpStatusCode.OK, renamed, renamed.bodyAsText())
            assertEquals("Platform", renamed.json().text("name"))
            assertEquals(emptyList(), renamed.json().strings("accounts"), "An empty list lends nothing")
            val lent = send("PATCH", "kira", "$base/$id", buildJsonObject { put("accounts", strings(listOf(max))) })
            assertEquals("Platform", lent.json().text("name"), "Only the accounts change")
            assertEquals(listOf(max), lent.json().strings("accounts"))
            val same = send("PATCH", "kira", "$base/$id", rename("platform"))
            assertEquals("platform", same.json().text("name"), "A group keeps its own name in another case")

            assertStatus(HttpStatusCode.NoContent, send("DELETE", "kira", "$base/$mobileId"), "delete mobile")
            assertEquals(
                listOf(id),
                send("GET", "kira", base).json().getValue("items").jsonArray.map { it.jsonObject.text("id") },
            )
        }
    }

    @Test
    fun anAdminChangesMembersAndARemovedAccountIsNoLongerLent() = serverTest(modules, overrides) { server ->
        with(server) {
            val (max, backend) = team()
            val id = backend.text("id")
            assertStatus(HttpStatusCode.NoContent, send("PUT", "kira", "$base/$id/members/mia"), "add mia")
            assertStatus(HttpStatusCode.NoContent, send("PUT", "kira", "$base/$id/members/mia"), "add mia again")
            assertStatus(
                HttpStatusCode.NoContent,
                send("POST", "ana", "$base/$id/members/liam/pause", pause(later)),
                "pause liam",
            )
            assertStatus(HttpStatusCode.NoContent, send("PUT", "kira", "$base/$id/members/liam"), "liam stays paused")
            val paused = send("GET", "liam", "$base/$id").json()
            assertEquals(listOf("liam", "mia"), paused.strings("members"))
            val entry = paused.getValue("paused").jsonArray.single().jsonObject
            assertEquals("liam" to later, entry.text("login") to entry.text("until"))
            assertStatus(
                HttpStatusCode.NoContent,
                send("POST", "kira", "$base/$id/members/liam/pause", pause("2001-01-01T00:00:00Z")),
                "a past instant ends the pause",
            )
            assertEquals(JsonArray(emptyList()), send("GET", "kira", "$base/$id").json().getValue("paused"))

            assertStatus(HttpStatusCode.NoContent, send("DELETE", "kira", "$base/$id/members/mia"), "remove mia")
            assertStatus(HttpStatusCode.NotFound, send("DELETE", "kira", "$base/$id/members/mia"), "mia is gone")
            assertEquals(listOf("liam"), send("GET", "kira", "$base/$id").json().strings("members"))

            // An account removed from the organization is no longer lent.
            assertStatus(HttpStatusCode.NoContent, send("DELETE", "kira", "/api/v1/accounts/$max"), "remove max")
            assertEquals(emptyList(), send("GET", "kira", "$base/$id").json().strings("accounts"))
        }
    }

    @Test
    fun malformedOrUnknownInputIsRefused() = serverTest(modules, overrides) { server ->
        with(server) {
            val kira = person("kira")
            val (org, _) = organization("kira-co", kira)
            join(org, person("liam"))
            person("stranger")
            val id = send("POST", "kira", base, group("Backend", listOf("liam"))).json().text("id")
            val gone = send("POST", "kira", base, group("Gone")).json().text("id")
            assertStatus(HttpStatusCode.NoContent, send("DELETE", "kira", "$base/$gone"), "delete gone")

            mapOf(
                "a blank name" to group("  "),
                "a control character" to group("a\u0007b"),
                "a person outside the organization" to group("X", listOf("stranger")),
                "an unknown login" to group("X", listOf("ghost")),
                "an account that is no id" to group("X", accounts = listOf("max")),
                "an account the organization does not share" to group("X", accounts = listOf(Uuid.random().toString())),
            ).forEach { (what, body) ->
                assertStatus(HttpStatusCode.BadRequest, send("POST", "kira", base, body), what)
            }
            assertStatus(
                HttpStatusCode.BadRequest,
                send("PATCH", "kira", "$base/$id", rename("")),
                "a blank rename",
            )
            assertStatus(
                HttpStatusCode.BadRequest,
                send("POST", "kira", "$base/$id/members/liam/pause", pause("tomorrow")),
                "until is no instant",
            )
            assertStatus(
                HttpStatusCode.BadRequest,
                send("POST", "kira", "$base/$id/members/liam/pause", buildJsonObject { }),
                "no until",
            )

            mapOf(
                "an id that is no uuid" to send("GET", "kira", "$base/backend"),
                "a deleted group" to send("GET", "kira", "$base/$gone"),
                "renaming a deleted group" to send("PATCH", "kira", "$base/$gone", rename("Y")),
                "deleting it again" to send("DELETE", "kira", "$base/$gone"),
                "adding to a deleted group" to send("PUT", "kira", "$base/$gone/members/liam"),
                "removing from a deleted group" to send("DELETE", "kira", "$base/$gone/members/liam"),
                "pausing in a deleted group" to send("POST", "kira", "$base/$gone/members/liam/pause", pause(later)),
                "adding a person outside the organization" to send("PUT", "kira", "$base/$id/members/stranger"),
                "removing someone not in the group" to send("DELETE", "kira", "$base/$id/members/kira"),
                "removing an unknown login" to send("DELETE", "kira", "$base/$id/members/ghost"),
                "pausing someone not in the group" to
                    send("POST", "kira", "$base/$id/members/kira/pause", pause(later)),
            ).forEach { (what, response) -> assertStatus(HttpStatusCode.NotFound, response, what) }
        }
    }

    @Test
    fun aMemberWhoLeftIsNamedByIdAndRemovedByIt() = serverTest(modules, overrides) { server ->
        with(server) {
            val kira = person("kira")
            val (org, _) = organization("kira-co", kira)
            val liamMember = join(org, person("liam"))
            val id = send("POST", "kira", base, group("Backend", listOf("liam"))).json().text("id")
            // Fixture only: the member leaves without the orgs module's use case.
            database.superuser.execute("DELETE FROM orgs_members WHERE id = '$liamMember'").getOrThrow()

            assertEquals(listOf(liamMember.toString()), send("GET", "kira", "$base/$id").json().strings("members"))
            assertStatus(
                HttpStatusCode.NoContent,
                send("DELETE", "kira", "$base/$id/members/$liamMember"),
                "removed by the id the group shows",
            )
            assertEquals(emptyList(), send("GET", "kira", "$base/$id").json().strings("members"))
        }
    }

    @Test
    fun anotherOrganizationSeesAndChangesNothing() = serverTest(modules, overrides) { server ->
        with(server) {
            val kira = person("kira")
            val (org, kiraMember) = organization("kira-co", kira)
            join(org, person("liam"))
            val max = account(org, kiraMember, "max")
            val id = send("POST", "kira", base, group("Backend", listOf("liam"), listOf(max))).json().text("id")
            val zoe = person("zoe")
            val (other, zoeMember) = organization("zoe-co", zoe)
            val zoeAccount = account(other, zoeMember, "zoe")

            assertEquals(JsonArray(emptyList()), send("GET", "zoe", base, org = other).json().getValue("items"))
            mapOf(
                "read" to send("GET", "zoe", "$base/$id", org = other),
                "rename" to send("PATCH", "zoe", "$base/$id", rename("Mine"), org = other),
                "delete" to send("DELETE", "zoe", "$base/$id", org = other),
                "add" to send("PUT", "zoe", "$base/$id/members/zoe", org = other),
                "remove" to send("DELETE", "zoe", "$base/$id/members/liam", org = other),
                "pause" to send("POST", "zoe", "$base/$id/members/liam/pause", pause(later), org = other),
                "list naming the other organization" to send("GET", "zoe", base, org = org),
                "create naming the other organization" to send("POST", "zoe", base, group("In"), org = org),
            ).forEach { (what, response) -> assertStatus(HttpStatusCode.NotFound, response, what) }
            assertStatus(
                HttpStatusCode.BadRequest,
                send("POST", "zoe", base, group("Borrow", accounts = listOf(max)), org = other),
                "another organization's account is not lent",
            )
            assertStatus(
                HttpStatusCode.BadRequest,
                send("PATCH", "kira", "$base/$id", buildJsonObject { put("accounts", strings(listOf(zoeAccount))) }),
                "nor lent to it",
            )

            val kept = send("GET", "kira", "$base/$id").json()
            assertEquals("Backend", kept.text("name"))
            assertEquals(listOf("liam"), kept.strings("members"))
            assertEquals(listOf(max), kept.strings("accounts"))
        }
    }

    @Test
    fun everyRouteNeedsACredential() = serverTest(modules, overrides) { server ->
        with(server) {
            val id = Uuid.random()
            mapOf(
                "list" to send("GET", null, base),
                "create" to send("POST", null, base, group("X")),
                "read" to send("GET", null, "$base/$id"),
                "change" to send("PATCH", null, "$base/$id", rename("X")),
                "delete" to send("DELETE", null, "$base/$id"),
                "add" to send("PUT", null, "$base/$id/members/liam"),
                "remove" to send("DELETE", null, "$base/$id/members/liam"),
                "pause" to send("POST", null, "$base/$id/members/liam/pause", pause(later)),
                "an unknown token" to send("GET", "nobody", base),
            ).forEach { (what, response) -> assertStatus(HttpStatusCode.Unauthorized, response, what) }
        }
    }
}
