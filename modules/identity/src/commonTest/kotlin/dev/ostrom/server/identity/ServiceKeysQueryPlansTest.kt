// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.infrastructure.LIVE_SERVICE_KEYS_SQL
import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The service-key list under `EXPLAIN ANALYZE`, on the serving role with row-level security, over a token table where
 * the organization's keys are a small part of the rows (thousands of agent tokens): it reads the partial index
 * `identity_api_tokens_service`, never a scan of the table.
 */
class ServiceKeysQueryPlansTest {
    private val orgA = Uuid.parse("00000000-0000-4000-8000-0000000000a1")
    private val orgB = Uuid.parse("00000000-0000-4000-8000-0000000000b1")
    private val user = Uuid.parse("00000000-0000-4000-8000-0000000000c1")

    // No `::` cast and no colon at all: the driver would read `:x` as a named parameter.
    private fun seed(org: Uuid, kind: String, count: Int, prefix: String): String =
        "INSERT INTO identity_api_tokens (id, user_id, org_id, name, kind, scopes, hint, agent_session_id, " +
            "secret_hash, created_at, expires_at, revoked_at) SELECT gen_random_uuid(), CAST('$user' AS uuid), " +
            "CAST('$org' AS uuid), 'seed', '$kind', 'sessions-read', 'hint', 'session-' || i, " +
            "md5('$prefix' || i), now() - i * INTERVAL '1 minute', NULL, " +
            "CASE WHEN i % 4 = 0 THEN now() END FROM generate_series(1, $count) AS i"

    @Test
    fun theServiceKeyListReadsItsIndex() = serverTest(listOf(IdentityModule)) { server ->
        // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
        val fixtures = server.database.superuser
        fixtures.execute(
            "INSERT INTO identity_users (id, login, display_name, created_at) " +
                "VALUES (CAST('$user' AS uuid), 'seed', 'Seed', now())",
        ).getOrThrow()
        fixtures.execute(seed(orgB, "AGENT", 20_000, "b")).getOrThrow()
        fixtures.execute(seed(orgA, "AGENT", 5_000, "a")).getOrThrow()
        fixtures.execute(seed(orgA, "SERVICE", 40, "s")).getOrThrow()
        fixtures.execute("VACUUM ANALYZE identity_api_tokens").getOrThrow()

        val plan = server.driver.withOrganization(orgA) { tx ->
            tx.fetchAll(
                Statement.create("EXPLAIN (ANALYZE, BUFFERS) $LIVE_SERVICE_KEYS_SQL")
                    .bind("org", orgA).bind("now", Clock.System.now()).bind("limit", 100),
            ).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
        assertFalse("Seq Scan on identity_api_tokens" in plan, "The list scans the table:\n$plan")
        assertTrue("identity_api_tokens_service" in plan, "The list skips its index:\n$plan")
    }
}
