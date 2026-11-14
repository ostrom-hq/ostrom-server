// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.notifications.infrastructure.DELETE_SQL
import run.ostrom.server.notifications.infrastructure.FORGET_BROWSERS_SQL
import run.ostrom.server.notifications.infrastructure.FORGET_CREDENTIAL_SQL
import run.ostrom.server.notifications.infrastructure.FORGET_TOKEN_SQL
import run.ostrom.server.notifications.infrastructure.KEEP_LATEST_SQL
import run.ostrom.server.notifications.infrastructure.OF_MEMBER_SQL
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

/**
 * The phones' writes under `EXPLAIN ANALYZE`, on the serving role with row-level security, over a table where one
 * member's phones are a small part of the rows: trimming a member's phones, removing one, reading them to notify the
 * member and forgetting those of a credential that ended read an index, never a scan of the table.
 */
class PushDevicesQueryPlansTest {
    private val orgA = Uuid.parse("00000000-0000-4000-8000-0000000000a1")
    private val orgB = Uuid.parse("00000000-0000-4000-8000-0000000000b1")
    private val member = Uuid.parse("00000000-0000-4000-8000-0000000000c1")

    // No `::` cast and no colon at all: the driver would read `:x` as a named parameter.
    private fun seed(org: Uuid, owner: String, count: Int, prefix: String): String =
        "INSERT INTO notifications_push_devices (org_id, id, member_id, push_token, provider, platform, locale, " +
            "created_at, seen_at, user_id, credential_id, browser) SELECT CAST('$org' AS uuid), gen_random_uuid(), " +
            "$owner, 'fcm-token-$prefix' || i, 'fcm', 'ios', NULL, now() - i * INTERVAL '1 minute', " +
            "now() - i * INTERVAL '1 minute', $owner, gen_random_uuid(), i % 2 = 0 FROM generate_series(1, $count) AS i"

    @Test
    fun trimmingAndRemovingAMembersPhonesReadTheirIndexes() = serverTest(listOf(NotificationsModule)) { server ->
        // Fixtures only: rows of two organizations at once and the statistics, which no request writes.
        val fixtures = server.database.superuser
        fixtures.execute(seed(orgB, "CAST(md5('b' || (i % 500)) AS uuid)", 20_000, "b")).getOrThrow()
        fixtures.execute(seed(orgA, "CAST(md5('a' || (i % 500)) AS uuid)", 5_000, "a")).getOrThrow()
        fixtures.execute(seed(orgA, "CAST('$member' AS uuid)", 12, "m")).getOrThrow()
        fixtures.execute("VACUUM ANALYZE notifications_push_devices").getOrThrow()

        // The member is also the person of the seeded rows (`user_id`): the same id serves both lookups.
        fun explain(sql: String) = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind("org_id", orgA)
        val statements = listOf(
            explain(KEEP_LATEST_SQL).bind("member_id", member).bind("keep", 10),
            explain(DELETE_SQL).bind("member_id", member).bind("id", Uuid.random()),
            explain(OF_MEMBER_SQL).bind("member_id", member),
            explain(FORGET_CREDENTIAL_SQL).bind("user_id", member).bind("credential_id", Uuid.random()),
            explain(FORGET_BROWSERS_SQL).bind("user_id", member),
            // A token FCM no longer knows: its device, by the primary key.
            explain(FORGET_TOKEN_SQL).bind("id", Uuid.random()).bind("push_token", "fcm-token"),
        )
        for (statement in statements) {
            val plan = server.driver.withOrganization(orgA) { tx ->
                tx.fetchAll(statement).getOrThrow()
                    .joinToString("\n") { it.get("QUERY PLAN").asString() }
            }
            assertFalse("Seq Scan on notifications_push_devices" in plan, "A scan of the table:\n$plan")
        }
    }
}
