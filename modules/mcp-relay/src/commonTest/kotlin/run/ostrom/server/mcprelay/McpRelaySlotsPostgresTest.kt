// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.mcprelay.domain.McpCallLimiter
import run.ostrom.server.mcprelay.domain.McpCallSlot
import run.ostrom.server.mcprelay.domain.McpLimits
import run.ostrom.server.mcprelay.infrastructure.McpCallSlotRepositoryImpl
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.mcprelay.infrastructure.McpRelayMinutes
import run.ostrom.server.mcprelay.infrastructure.McpRelayServers
import run.ostrom.server.mcprelay.infrastructure.McpRelaySlots
import run.ostrom.server.kernel.database.databaseNowPlus
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The relay's limits across replicas on real PostgreSQL: two limiters over one database, as two replicas of the
 * server, never pass a limit together, and a slot whose replica died frees itself.
 */
class McpRelaySlotsPostgresTest : RelayPostgresHarness() {
    private val member = Relay.memberA.memberId
    private val other = Relay.otherA.memberId

    private fun TestServer.replica(limits: McpLimits) =
        McpCallLimiter(limits, McpCallSlotRepositoryImpl(koin.get<JdbcDatabase>()))

    private suspend fun TestServer.serverId(): Uuid {
        grafana()
        return database.superuserJdbc.query { McpRelayServers.select(McpRelayServers.id).single()[McpRelayServers.id] }
    }

    @Test
    fun twoReplicasAdmittingAtOnceNeverRunMoreThanTheConcurrency() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            val serverId = server.serverId()
            val limits = McpLimits(memberConcurrency = 3)
            val replicas = listOf(server.replica(limits), server.replica(limits))
            val slots = coroutineScope {
                (0 until 12).map { i -> async { replicas[i % 2].acquire(Relay.orgA, member, serverId) } }.awaitAll()
            }
            assertEquals(3, slots.count { it != null }, "Three running calls across both replicas, not three each")

            replicas[0].release(Relay.orgA, slots.first { it != null }!!)
            assertNotNull(replicas[1].acquire(Relay.orgA, member, serverId), "A slot freed on one is seen on the other")
            assertNotNull(replicas[1].acquire(Relay.orgA, other, serverId), "Another member is not held back")
        }

    @Test
    fun theMinutesCountIsSharedByBothReplicas() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            val serverId = server.serverId()
            // Not across a minute's turn, by the database's clock.
            val second = server.database.superuserJdbc.query {
                exec("SELECT CAST(EXTRACT(SECOND FROM now()) AS BIGINT) AS s") { rs -> rs.next(); rs.getLong("s") }
            }!!
            if (second >= 50) delay((61 - second).seconds)
            val limits = McpLimits(memberPerMinute = 2)
            val (a, b) = server.replica(limits) to server.replica(limits)
            a.release(Relay.orgA, assertNotNull(a.acquire(Relay.orgA, member, serverId)))
            b.release(Relay.orgA, assertNotNull(b.acquire(Relay.orgA, member, serverId)))
            assertNull(a.acquire(Relay.orgA, member, serverId), "Two this minute across both replicas")
            assertNull(b.acquire(Relay.orgA, member, serverId))
        }

    @Test
    fun aSlotWhoseReplicaDiedFreesItselfAndAnotherOrgSeesNone() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            val serverId = server.serverId()
            val limits = McpLimits(memberConcurrency = 1)
            val dead = server.replica(limits)
            assertNotNull(dead.acquire(Relay.orgA, member, serverId))
            val alive = server.replica(limits)
            assertNull(alive.acquire(Relay.orgA, member, serverId))
            // A running operation renews its slot for a whole lease, by the database's clock.
            val superuser = server.database.superuserJdbc
            val id = superuser.query { McpRelaySlots.select(McpRelaySlots.id).single()[McpRelaySlots.id] }
            superuser.query { McpRelaySlots.update { it[expiresAt] = databaseNowPlus(1.seconds) } }
            McpCallSlotRepositoryImpl(server.koin.get<JdbcDatabase>()).renew(Relay.orgA, McpCallSlot(id))
            val renewed = superuser.query {
                McpRelaySlots.select(McpRelaySlots.id)
                    .where { McpRelaySlots.expiresAt greater databaseNowPlus(14.minutes) }.toList().size
            }
            assertEquals(1, renewed)
            superuser.query { McpRelaySlots.update { it[expiresAt] = databaseNowPlus((-1).seconds) } }
            assertNotNull(alive.acquire(Relay.orgA, member, serverId), "The expired slot no longer counts")

            assertNull(alive.acquire(Relay.orgB, Relay.adminB.memberId, serverId), "Org B cannot admit on A's server")
            listOf<Table>(McpRelaySlots, McpRelayMinutes).forEach { table ->
                val seen = server.koin.get<JdbcDatabase>().inOrg(Relay.orgB) { table.selectAll().count() }
                assertEquals(0L, seen, "Org B sees none of A's ${table.tableName}")
            }
        }
}
