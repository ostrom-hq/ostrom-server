// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications

import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.notifications.infrastructure.DELETE_SQL
import dev.ostrom.server.notifications.infrastructure.KEEP_LATEST_SQL
import dev.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

/**
 * The phones' writes under `EXPLAIN ANALYZE`, on the serving role with row-level security, over a table where one
 * member's phones are a small part of the rows: trimming a member's phones and removing one read an index, never a
 * scan of the table.
 */
class PushDevicesQueryPlansTest {
    private val orgA = Uuid.parse("00000000-0000-4000-8000-0000000000a1")
    private val orgB = Uuid.parse("00000000-0000-4000-8000-0000000000b1")
    private val member = Uuid.parse("00000000-0000-4000-8000-0000000000c1")

    // No `::` cast and no colon at all: the driver would read `:x` as a named parameter.
    private fun seed(org: Uuid, owner: String, count: Int, prefix: String): String =
        "INSERT INTO notifications_push_devices (org_id, id, member_id, push_token, provider, platform, locale, " +
            "created_at, seen_at) SELECT CAST('$org' AS uuid), gen_random_uuid(), $owner, " +
            "'ExpoPushToken[$prefix' || i || ']', 'expo', 'ios', NULL, now() - i * INTERVAL '1 minute', " +
            "now() - i * INTERVAL '1 minute' FROM generate_series(1, $count) AS i"

    @Test
    fun trimmingAndRemovingAMembersPhonesReadTheirIndexes() = serverTest(listOf(NotificationsModule)) { server ->
        // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
        val fixtures = server.database.superuser
        fixtures.execute(seed(orgB, "CAST(md5('b' || (i % 500)) AS uuid)", 20_000, "b")).getOrThrow()
        fixtures.execute(seed(orgA, "CAST(md5('a' || (i % 500)) AS uuid)", 5_000, "a")).getOrThrow()
        fixtures.execute(seed(orgA, "CAST('$member' AS uuid)", 12, "m")).getOrThrow()
        fixtures.execute("VACUUM ANALYZE notifications_push_devices").getOrThrow()

        val statements = listOf(
            Statement.create("EXPLAIN (ANALYZE, BUFFERS) $KEEP_LATEST_SQL").bind("keep", 10),
            Statement.create("EXPLAIN (ANALYZE, BUFFERS) $DELETE_SQL").bind("id", Uuid.random()),
        )
        for (statement in statements) {
            val plan = server.driver.withOrganization(orgA) { tx ->
                tx.fetchAll(statement.bind("org_id", orgA).bind("member_id", member)).getOrThrow()
                    .joinToString("\n") { it.get("QUERY PLAN").asString() }
            }
            assertFalse("Seq Scan on notifications_push_devices" in plan, "A scan of the table:\n$plan")
        }
    }
}
