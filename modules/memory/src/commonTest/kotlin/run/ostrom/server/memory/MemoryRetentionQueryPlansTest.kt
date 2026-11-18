// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory

import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.memory.domain.MemoryProposalWaits
import run.ostrom.server.memory.fakes.Callers
import run.ostrom.server.memory.fakes.FakeProposalWaits
import run.ostrom.server.memory.fakes.ORG_A
import run.ostrom.server.memory.fakes.ORG_B
import run.ostrom.server.memory.fakes.Tokens
import run.ostrom.server.memory.infrastructure.expiredEntries
import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * The read of the expired entries a curation run deletes under `EXPLAIN ANALYZE`, in `inOrg` (the tenant role, row
 * level security applies), over 25 000 entries of two organizations, one in ten with an expiry: it reads only the
 * organization's expired entries, through their index, never a scan of every entry (the database audit of 18/11).
 */
class MemoryRetentionQueryPlansTest {
    private val overrides = module {
        single<TokenAuthenticator> { Tokens }
        single<CallerResolver> { Callers }
        single<MemoryProposalWaits> { FakeProposalWaits() }
    }

    // No `::` cast and no colon at all: the driver would read `:x` as a named parameter.
    private fun seed(org: String, count: Int): String =
        "INSERT INTO memory_entries (id, org_id, scope, owner_id, title, summary, content, refs, status, origin, " +
            "author_id, version, created_at, updated_at, expires_at) SELECT gen_random_uuid(), CAST('$org' AS uuid), " +
            "'team', '', 'Note ' || i, 'Summary', 'c', '[]', 'active', 'human', 'ada', 1, now(), now(), " +
            "CASE WHEN i % 10 = 0 THEN now() - (i % 90) * INTERVAL '1 day' END FROM generate_series(1, $count) AS i"

    @Test
    fun theExpiredEntriesOfARunAreReadThroughTheirIndex() = serverTest(listOf(MemoryModule), overrides) { server ->
        // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
        server.database.superuserOutsideTransaction(
            seed("$ORG_B", 20_000),
            seed("$ORG_A", 5_000),
            "VACUUM ANALYZE memory_entries",
        )
        val database = server.koin.get<JdbcDatabase>()
        val before = Clock.System.now() - 30.days
        val plan = database.inOrg(ORG_A) { plan { expiredEntries(ORG_A, before, 1_000) } }
        assertFalse("Seq Scan on memory_entries" in plan, "A scan of every entry:\n$plan")
        // The organization's other indexes would read every entry of a status to keep the expired ones.
        assertTrue("memory_entries_expiry" in plan, "Not the expiries' index:\n$plan")
    }
}
