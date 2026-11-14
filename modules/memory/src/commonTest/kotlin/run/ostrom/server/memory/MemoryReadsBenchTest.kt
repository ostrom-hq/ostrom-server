// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.core.api.models.MemoryIndexPage
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.memory.domain.MemoryProposalWaits
import run.ostrom.server.memory.fakes.ADA
import run.ostrom.server.memory.fakes.Callers
import run.ostrom.server.memory.fakes.FakeProposalWaits
import run.ostrom.server.memory.fakes.ORG_A
import run.ostrom.server.memory.fakes.ORG_B
import run.ostrom.server.memory.fakes.Tokens
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.testing.assertWithinBudget
import run.ostrom.server.testing.benchTest
import run.ostrom.server.testing.measureRead
import run.ostrom.server.testing.writeReadReport
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * The memory's reads on 10 organizations each holding the team limit (5 000 entries) and 20 members' 500 personal
 * entries, two tags each: the index (both scopes, a tag filter, the next page), the search and an entry read on
 * demand keep the read budget (ReadBench.kt), at most three queries a call; the report is
 * `build/reports/perf/memory.txt`.
 */
class MemoryReadsBenchTest {
    private val overrides = module {
        single<TokenAuthenticator> { Tokens }
        single<CallerResolver> { Callers }
        single<MemoryProposalWaits> { FakeProposalWaits() }
    }
    private val orgs = listOf(ORG_A, ORG_B) + List(ORGS - 2) { Uuid.random() }

    // No colon in the seeds: the driver would read `:x` as a named parameter. Ada is one of A's members.
    private fun entriesOf(org: Uuid): String =
        "INSERT INTO memory_entries (id, org_id, scope, owner_id, title, summary, content, refs, status, origin, " +
            "author_id, version, created_at, updated_at) SELECT gen_random_uuid(), CAST('$org' AS uuid), " +
            "CASE WHEN i <= $TEAM THEN 'team' ELSE 'personal' END, " +
            "CASE WHEN i <= $TEAM THEN '' WHEN i % $MEMBERS = 0 THEN '$ADA' ELSE 'member-' || (i % $MEMBERS) END, " +
            "'Note ' || i || CASE WHEN i % 100 = 0 THEN ' deploy' ELSE '' END, 'Summary ' || i, repeat('c', 200), " +
            "'[]', 'active', 'human', '$ADA', 1, now(), now() - i * INTERVAL '1 second' " +
            "FROM generate_series(1, ${TEAM + MEMBERS * PERSONAL}) AS i"

    private fun tagsOf(org: Uuid): String =
        "INSERT INTO memory_tags (memory_id, tag, org_id, scope, owner_id) SELECT id, t, org_id, scope, owner_id " +
            "FROM memory_entries, unnest(ARRAY['area-' || (abs(hashtext(title)) % 40), 'kind-' || " +
            "(abs(hashtext(summary)) % 8)]) AS t WHERE org_id = CAST('$org' AS uuid)"

    @Test
    fun theReadsKeepTheirBudget() = benchTest(listOf(MemoryModule), overrides) { server ->
        val fixtures = server.database.superuser
        orgs.forEach {
            fixtures.execute(entriesOf(it)).getOrThrow()
            fixtures.execute(tagsOf(it)).getOrThrow()
        }
        fixtures.execute("VACUUM ANALYZE memory_entries").getOrThrow()
        fixtures.execute("VACUUM ANALYZE memory_tags").getOrThrow()

        val first = server.client.get("/api/v1/memory/index") { bearerAuth("ada") }.body<MemoryIndexPage>()
        assertEquals(50, first.entries.size)
        val id = first.entries.first().id
        val measures = listOf(
            server.measureRead("GET /api/v1/memory/index (both scopes)") {
                get("/api/v1/memory/index") { bearerAuth("ada") }
            },
            server.measureRead("GET /api/v1/memory/index (a tag, the next page)") {
                get("/api/v1/memory/index?tags=area-3,kind-1&after=${first.next}") { bearerAuth("ada") }
            },
            server.measureRead("GET /api/v1/memory/search (two terms)") {
                get("/api/v1/memory/search?q=deploy%20note") { bearerAuth("ada") }
            },
            server.measureRead("GET /api/v1/memory/{id} (counts the read)", writes = true) {
                get("/api/v1/memory/$id") { bearerAuth("ada") }
            },
        )
        writeReadReport("memory", measures)
        measures.forEach { it.assertWithinBudget(setOf("memory_entries", "memory_tags"), MAX_QUERIES) }
    }

    private companion object {
        const val ORGS = 10
        const val TEAM = 5_000
        const val MEMBERS = 20
        const val PERSONAL = 500

        // One read per scope and one for the tags of the rows returned (a read: the entry, its count, its tags).
        const val MAX_QUERIES = 3
    }
}
