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
        val whole = response.body<MembersResponse>().items
        assertEquals(MEMBERS_PER_ORG, whole.size)
        val middle = whole[MEMBERS_PER_ORG / 2].id
        val measures = listOf(
            "GET /api/v1/orgs/{org}/members (1 000 members)" to "/api/v1/orgs/org0/members",
            "GET /api/v1/orgs/{org}/members/{login}" to "/api/v1/orgs/org0/members/user0-500",
            "GET /api/v1/orgs/{org}/members?limit=100&after= (a middle page)" to
                "/api/v1/orgs/org0/members?limit=$PAGE&after=$middle",
            "GET /api/v1/orgs/{org}/members?q= (a search of the whole team)" to
                "/api/v1/orgs/org0/members?q=user0-4&limit=$PAGE",
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

    // WEB's console searches the members on the server: a prefix of the login or the name, whatever the case, paged.
    @Test
    fun theSearchKeepsTheMembersItsPrefixStarts() = benchTest(listOf(IdentityModule, OrgsModule), null) { server ->
        val owner = server.owner(0)
        membersOf(0, PAGED_MEMBERS).forEach { server.database.superuser.execute(it).getOrThrow() }
        val base = "/api/v1/orgs/org0/members"
        // Members 4, 40 to 49 and 400 to 499: login `user0-4…`, name `User 4…`.
        val expected = (2..PAGED_MEMBERS).filter { it.toString().startsWith("4") }.map { "user0-$it" }.toSet()

        for (q in listOf("USER0-4", "User 4")) {
            val seen = mutableListOf<String>()
            var after: String? = null
            var pages = 0
            do {
                val query = "?q=${q.encodeURLParameter()}&limit=$PAGE" +
                    (after?.let { "&after=${it.encodeURLParameter()}" } ?: "")
                val page = server.client.get(base + query) { session(owner) }.body<JsonObject>()
                seen += page.getValue("items").jsonArray.map { it.jsonObject.getValue("login").jsonPrimitive.content }
                after = page["next"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                pages++
            } while (after != null && pages <= PAGED_MEMBERS / PAGE)
            assertEquals(expected, seen.toSet(), q)
            assertEquals(expected.size, seen.size, "Each member once for $q")
            assertEquals(2, pages, q)
        }
        val none = server.client.get("$base?q=nobody") { session(owner) }.body<JsonObject>()
        assertTrue(none.getValue("items").jsonArray.isEmpty())
        assertTrue(none["next"] == null || none["next"] is JsonNull)
        for (query in listOf("q=", "q=" + "x".repeat(MAX_QUERY + 1))) {
            assertEquals(HttpStatusCode.BadRequest, server.client.get("$base?$query") { session(owner) }.status, query)
        }
    }

    // The case that asked for `q`: a team of 5 000, a keystroke every 250 ms, among 10 such organizations (with two,
    // one team is half of each table and the planner rightly reads it whole). Each search keeps the read budget on
    // the median of three trials: the first letter (every member), a prefix of 1 111, and one member.
    @Test
    fun theSearchOfAFiveThousandTeamKeepsItsBudget() = benchTest(listOf(IdentityModule, OrgsModule), null) { server ->
        val owners = (0 until LARGE_ORGS).map { server.owner(it) }
        (0 until LARGE_ORGS).flatMap { membersOf(it, LARGE_TEAM) }.forEach {
            server.database.superuser.execute(it).getOrThrow()
        }
        server.database.superuser.execute("VACUUM ANALYZE identity_users").getOrThrow()
        server.database.superuser.execute("VACUUM ANALYZE orgs_members").getOrThrow()
        val searches = listOf("u", "user0-4", "user0-4999").map { q ->
            val path = "/api/v1/orgs/org0/members?q=$q&limit=$PAGE"
            val trials = (1..TRIALS).map {
                server.measureRead("GET /api/v1/orgs/{org}/members?q=$q (5 000 members, trial $it)") {
                    get(path) { session(owners.first()) }
                }
            }
            trials.sortedBy { it.p95 }[TRIALS / 2]
        }
        writeReadReport("orgs-search", searches)
        server.explainSearch()
        searches.forEach { it.assertWithinBudget(setOf("orgs_members", "identity_users")) }
        server.searchMemory(owners.first())
    }

    // The bench's EXPLAIN shows the function call only: its two steps, planned with each prefix as its
    // `EXECUTE … USING` plans them, must read neither table whole either. A rare prefix (at most 500 people) reads
    // their memberships by key, a common one walks the team in order.
    private suspend fun TestServer.explainSearch() {
        val org = "CAST(md5('org0') AS uuid)"
        for ((q, rare) in listOf("u" to false, "user0-4" to true, "user0-4999" to true)) {
            val named = "lower(u.login) LIKE '$q%' OR lower(u.display_name) LIKE '$q%'"
            val people = "SELECT c.id FROM ((SELECT u.id FROM identity_users u WHERE lower(u.login) LIKE '$q%' " +
                "ORDER BY lower(u.login) USING ~<~ LIMIT 501) UNION (SELECT u.id FROM identity_users u " +
                "WHERE lower(u.display_name) LIKE '$q%' ORDER BY lower(u.display_name) USING ~<~ LIMIT 501)) c " +
                "LIMIT 501"
            val page = if (rare) {
                "SELECT m.id FROM orgs_members m WHERE m.org_id = $org AND m.user_id = ANY (ARRAY($people)) " +
                    "ORDER BY m.joined_at, m.id LIMIT ${PAGE + 1}"
            } else {
                "SELECT m.id FROM orgs_members m CROSS JOIN LATERAL (SELECT 1 FROM identity_users u " +
                    "WHERE u.id = m.user_id AND ($named) LIMIT 1) p WHERE m.org_id = $org " +
                    "ORDER BY m.joined_at, m.id LIMIT ${PAGE + 1}"
            }
            for ((step, sql) in listOf("people" to people, "page" to page)) {
                val plan = database.superuser.fetchAll("EXPLAIN (ANALYZE, BUFFERS) $sql").getOrThrow()
                    .joinToString("\n") { it.get("QUERY PLAN").asString() }
                println("== EXPLAIN orgs_search_members q=$q, $step (5 000 members)\n$plan")
                assertTrue("Seq Scan" !in plan, "q=$q ($step) reads a table whole:\n$plan")
            }
        }
    }

    // The memory of a search, through the whole call, where the platform counts it (the JVM): the bytes allocated per
    // search over 20 in a row, and the heap's peak meanwhile. Printed for the PR; bounded so a regression shows.
    private suspend fun TestServer.searchMemory(owner: StartedSession) {
        for (q in listOf("u", "user0-4")) {
            val path = "/api/v1/orgs/org0/members?q=$q&limit=$PAGE"
            client.get(path) { session(owner) }
            resetHeapPeak()
            val before = allocatedBytes() ?: return
            repeat(MEMORY_CALLS) { assertTrue(client.get(path) { session(owner) }.status.isSuccess()) }
            val perSearch = ((allocatedBytes() ?: before) - before) / MEMORY_CALLS
            println("memory q=$q (5 000 members): ${perSearch / KIB} KiB allocated per search, heap peak " +
                "${(heapPeakBytes() ?: 0) / KIB / KIB} MiB")
            assertTrue(perSearch < MAX_SEARCH_BYTES, "q=$q allocates $perSearch bytes per search")
        }
    }

    private companion object {
        const val KIB = 1024L
        const val MEMORY_CALLS = 20
        const val MAX_SEARCH_BYTES = 64L * 1024 * 1024
        const val LARGE_ORGS = 10
        const val LARGE_TEAM = 5_000
        const val TRIALS = 3
        const val MAX_QUERY = 100
        const val PAGE = 100
        const val PAGED_MEMBERS = 500

        // Seven queries per read today (#206), one of margin.
        const val MAX_QUERIES = 8
        const val ORGS = 10
        const val MEMBERS_PER_ORG = 1_000
    }
}
