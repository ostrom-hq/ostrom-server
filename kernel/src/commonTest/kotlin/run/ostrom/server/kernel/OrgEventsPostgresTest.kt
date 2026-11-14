// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.coroutines.test.runTest
import run.ostrom.server.kernel.events.DiscardingOrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.testing.TestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** The default publisher inside a caller's transaction: it writes nothing and leaves the transaction usable. */
class OrgEventsPostgresTest {
    @Test
    fun theDiscardingPublisherWritesNothingInTheCallersTransaction() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            val answer = database.owner.transaction {
                DiscardingOrgEventPublisher().publishIn(
                    this,
                    Uuid.random(),
                    listOf(OrgEventDraft(OrgEventType.WAIT_RESOLVED, "wait-1", Uuid.random())),
                )
                fetchAll(Statement.create("SELECT 1 AS one")).getOrThrow().first().get("one").asLong()
            }
            assertEquals(1L, answer)
        } finally {
            database.close()
        }
    }
}
