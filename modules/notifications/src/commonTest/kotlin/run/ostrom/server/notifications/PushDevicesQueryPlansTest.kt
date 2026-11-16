// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.notifications.infrastructure.deleteDevice
import run.ostrom.server.notifications.infrastructure.devicesOfMember
import run.ostrom.server.notifications.infrastructure.forgetCredentialDevices
import run.ostrom.server.notifications.infrastructure.forgetTokenDevice
import run.ostrom.server.notifications.infrastructure.keepLatest
import run.ostrom.server.testing.plan
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
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
        server.database.superuserOutsideTransaction(
            seed(orgB, "CAST(md5('b' || (i % 500)) AS uuid)", 20_000, "b"),
            seed(orgA, "CAST(md5('a' || (i % 500)) AS uuid)", 5_000, "a"),
            seed(orgA, "CAST('$member' AS uuid)", 12, "m"),
            "VACUUM ANALYZE notifications_push_devices",
        )

        // The member is also the person of the seeded rows (`user_id`): the same id serves both lookups.
        val statements: List<StatementBuilder.() -> Statement<*>> = listOf(
            { keepLatest(orgA, member, 10) },
            { deleteDevice(orgA, member, Uuid.random()) },
            { devicesOfMember(orgA, member) },
            { forgetCredentialDevices(orgA, member, Uuid.random()) },
            { forgetCredentialDevices(orgA, member, null) },
            // A token FCM no longer knows: its device, by the primary key.
            { forgetTokenDevice(orgA, Uuid.random(), "fcm-token") },
        )
        val database = server.koin.get<JdbcDatabase>()
        for (statement in statements) {
            val plan = database.withOrganization(orgA) { plan(statement).also { rollback() } }
            assertFalse("Seq Scan on notifications_push_devices" in plan, "A scan of the table:\n$plan")
        }
    }
}
