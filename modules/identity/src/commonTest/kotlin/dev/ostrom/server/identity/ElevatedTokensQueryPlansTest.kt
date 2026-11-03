// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.infrastructure.LIVE_ELEVATED_TOKENS_OF_USER_SQL
import dev.ostrom.server.identity.infrastructure.LIVE_ELEVATED_TOKENS_SQL
import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The lists of `ostrom login --admin` tokens in `/devices` under `EXPLAIN ANALYZE`, on the serving role with
 * row-level security, over a token table where the organization's elevated tokens are a small part of the rows
 * (thousands of agent tokens and service keys): neither scans the table, and the list of every member's reads the
 * partial index `identity_api_tokens_elevated`.
 */
class ElevatedTokensQueryPlansTest {
    private val orgA = Uuid.parse("00000000-0000-4000-8000-0000000000a3")
    private val orgB = Uuid.parse("00000000-0000-4000-8000-0000000000b3")
    private val user = Uuid.parse("00000000-0000-4000-8000-0000000000c3")
    private val admin = Uuid.parse("00000000-0000-4000-8000-0000000000d3")

    // No `::` cast and no colon at all: the driver would read `:x` as a named parameter.
    private fun seed(org: Uuid, owner: Uuid, kind: String, count: Int, prefix: String): String =
        "INSERT INTO identity_api_tokens (id, user_id, org_id, name, kind, scopes, hint, agent_session_id, " +
            "secret_hash, created_at, expires_at, revoked_at) SELECT gen_random_uuid(), CAST('$owner' AS uuid), " +
            "CAST('$org' AS uuid), 'seed', '$kind', 'sessions-read', 'hint', 'session-' || i, " +
            "md5('$prefix' || i), now() - i * INTERVAL '1 minute', NULL, " +
            "CASE WHEN i % 4 = 0 THEN now() END FROM generate_series(1, $count) AS i"

    private fun person(id: Uuid, login: String): String =
        "INSERT INTO identity_users (id, login, display_name, created_at) " +
            "VALUES (CAST('$id' AS uuid), '$login', '$login', now())"

    @Test
    fun theListsOfElevatedTokensReadAnIndex() = serverTest(listOf(IdentityModule)) { server ->
        // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
        val fixtures = server.database.superuser
        fixtures.execute(person(user, "seed")).getOrThrow()
        fixtures.execute(person(admin, "admin")).getOrThrow()
        fixtures.execute(seed(orgB, user, "AGENT", 20_000, "b")).getOrThrow()
        fixtures.execute(seed(orgA, user, "AGENT", 5_000, "a")).getOrThrow()
        fixtures.execute(seed(orgA, user, "SERVICE", 40, "s")).getOrThrow()
        fixtures.execute(seed(orgA, admin, "ADMIN", 40, "e")).getOrThrow()
        fixtures.execute(seed(orgB, admin, "ADMIN", 400, "f")).getOrThrow()
        fixtures.execute("VACUUM ANALYZE identity_api_tokens").getOrThrow()

        val all = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $LIVE_ELEVATED_TOKENS_SQL")
        val everyMembers = plan(server.driver, all)
        assertFalse("Seq Scan on identity_api_tokens" in everyMembers, "The list scans the table:\n$everyMembers")
        assertTrue("identity_api_tokens_elevated" in everyMembers, "The list skips its index:\n$everyMembers")

        val ofOne = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $LIVE_ELEVATED_TOKENS_OF_USER_SQL").bind("user", admin)
        val own = plan(server.driver, ofOne)
        assertFalse("Seq Scan on identity_api_tokens" in own, "A member's list scans the table:\n$own")
    }

    private suspend fun plan(driver: Driver, statement: Statement): String =
        driver.withOrganization(orgA) { tx ->
            tx.fetchAll(statement.bind("org", orgA).bind("now", Clock.System.now()).bind("limit", 100))
                .getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
        }
}
