// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.orgs.presentation.MembersResponse
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.assertWithinBudget
import dev.ostrom.server.testing.benchTest
import dev.ostrom.server.testing.measureRead
import dev.ostrom.server.testing.writeReadReport
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET /api/v1/orgs/{org}/members` on 10 organizations of 1 000 members each: the whole list and one member. Each
 * keeps the read budget (ReadBench.kt); its queries, plans and p95 are written to `build/reports/perf/orgs.txt`.
 */
class OrgsReadsBenchTest {
    // Organizations are written directly: the API gives a person one free organization, 402 for the next ones.
    private suspend fun TestServer.owner(index: Int): StartedSession {
        val login = "owner$index"
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        database.superuser.execute(
            "INSERT INTO orgs_organizations (id, slug, name, created_at) VALUES " +
                "(CAST(md5('org$index') AS uuid), 'org$index', 'Org $index', now())",
        ).getOrThrow()
        database.superuser.execute(
            "INSERT INTO orgs_members (id, org_id, user_id, role, joined_at) VALUES (gen_random_uuid(), " +
                "CAST(md5('org$index') AS uuid), CAST('${user.id}' AS uuid), 'owner', now())",
        ).getOrThrow()
        return koin.get<StartWebSessionUseCase>()(user.id)
    }

    private fun HttpRequestBuilder.session(started: StartedSession) {
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=${started.secret}; $CSRF_COOKIE=${started.csrfToken}")
        header(CSRF_HEADER, started.csrfToken)
    }

    // No `::` cast and no colon at all in the seeds: the driver would read `:x` as a named parameter.
    private fun membersOf(index: Int, count: Int = MEMBERS_PER_ORG) = listOf(
        "INSERT INTO identity_users (id, login, display_name, created_at) SELECT CAST(md5('u$index-' || i) AS uuid), " +
            "'user$index-' || i, 'User ' || i, now() FROM generate_series(2, $count) AS i",
        "INSERT INTO orgs_members (id, org_id, user_id, role, joined_at) SELECT gen_random_uuid(), " +
            "CAST(md5('org$index') AS uuid), CAST(md5('u$index-' || i) AS uuid), " +
            "CASE WHEN i % 10 = 0 THEN 'admin' ELSE 'member' END, now() - i * INTERVAL '1 hour' " +
            "FROM generate_series(2, $count) AS i",
    )

    @Test
    fun theMemberListKeepsItsBudget() = benchTest(listOf(IdentityModule, OrgsModule), null) { server ->
        val sessions = (0 until ORGS).map { server.owner(it) }
        val fixtures = server.database.superuser
        (0 until ORGS).flatMap { membersOf(it) }.forEach { fixtures.execute(it).getOrThrow() }
        fixtures.execute("VACUUM ANALYZE identity_users").getOrThrow()
        fixtures.execute("VACUUM ANALYZE orgs_members").getOrThrow()

        val response = server.client.get("/api/v1/orgs/org0/members") { session(sessions.first()) }
        assertTrue(response.status.isSuccess(), response.status.toString())
        assertEquals(MEMBERS_PER_ORG, response.body<MembersResponse>().items.size)
        val measures = listOf(
            "GET /api/v1/orgs/{org}/members (1 000 members)" to "/api/v1/orgs/org0/members",
            "GET /api/v1/orgs/{org}/members/{login}" to "/api/v1/orgs/org0/members/user0-500",
        ).map { (name, path) -> server.measureRead(name) { get(path) { session(sessions.first()) } } }
        writeReadReport("orgs", measures)
        measures.forEach { it.assertWithinBudget(setOf("orgs_members", "identity_users")) }
        // No N+1: the list of 1 000 sends the queries of one member, and neither sends more than a handful.
        val described = measures.joinToString("\n") { it.describe() }
        assertEquals(measures[1].queries, measures[0].queries, described)
        assertTrue(measures[0].queries <= MAX_QUERIES, described)
    }

    // WEB's console pages the members 100 at a time: 500 members are 5 pages, each id once, oldest first.
    @Test
    fun theMemberListPagesByItsCursor() = benchTest(listOf(IdentityModule, OrgsModule), null) { server ->
        val owner = server.owner(0)
        membersOf(0, PAGED_MEMBERS).forEach { server.database.superuser.execute(it).getOrThrow() }
        val base = "/api/v1/orgs/org0/members"

        val seen = mutableListOf<String>()
        var after: String? = null
        var pages = 0
        do {
            val query = "?limit=$PAGE" + (after?.let { "&after=${it.encodeURLParameter()}" } ?: "")
            val page = server.client.get(base + query) { session(owner) }.body<JsonObject>()
            val items = page.getValue("items").jsonArray
            assertTrue(items.size <= PAGE, "A page holds at most its limit, not ${items.size}")
            seen += items.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            after = page["next"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            pages++
        } while (after != null && pages <= PAGED_MEMBERS / PAGE)
        assertEquals(PAGED_MEMBERS / PAGE, pages)
        assertEquals(PAGED_MEMBERS, seen.toSet().size, "Every member once")
        val whole = server.client.get(base) { session(owner) }.body<JsonObject>().getValue("items").jsonArray
        assertEquals(seen, whole.map { it.jsonObject.getValue("id").jsonPrimitive.content }, "Without a limit, all")

        for (query in listOf("limit=0", "limit=100000", "after=nope")) {
            assertEquals(HttpStatusCode.BadRequest, server.client.get("$base?$query") { session(owner) }.status, query)
        }
    }

    private companion object {
        const val PAGE = 100
        const val PAGED_MEMBERS = 500

        // Seven queries per read today (#206), one of margin.
        const val MAX_QUERIES = 8
        const val ORGS = 10
        const val MEMBERS_PER_ORG = 1_000
    }
}
