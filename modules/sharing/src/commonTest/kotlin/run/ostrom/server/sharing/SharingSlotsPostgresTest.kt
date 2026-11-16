// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.databaseNowPlus
import run.ostrom.server.sharing.infrastructure.SharingSlots
import run.ostrom.server.sharing.infrastructure.SharingAffinities
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountRefreshRepository
import run.ostrom.server.sharing.domain.AccountSlotRepository
import run.ostrom.server.sharing.domain.AccountSlot
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.AccountTokens
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.NoAccountSpendReader
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.QuotaWindowRepository
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.sharing.domain.SharedAccountRepository
import run.ostrom.server.sharing.infrastructure.AccountSlotRepositoryImpl
import run.ostrom.server.sharing.infrastructure.sessionHash
import run.ostrom.server.sharing.presentation.SharedAccountResponse
import run.ostrom.server.sharing.presentation.SharedAccountsResponse
import run.ostrom.server.sharing.presentation.UpdateAccountRequest
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Real PostgreSQL, two pools over one database as two replicas: an account's concurrency and a session's account hold
 * whichever replica a request reaches, and a dead replica's slot frees itself.
 */
class SharingSlotsPostgresTest : SharingPostgresHarness() {
    /** Another replica's pool over the same database: its own instances, nothing in common but PostgreSQL. */
    private fun TestServer.replicaPool() = SharedAccountPool(
        koin.get(),
        koin.get(),
        AccountTokens(
            koin.get<AccountRefreshRepository>(),
            koin.get<AccountTokenCipher>(),
            FakeRefresher(),
            Clock.System,
            JsonLogger(LogLevel.ERROR, sink = {}),
        ),
        AccountSlotRepositoryImpl(koin.get()),
        koin.get(),
        NoAccountSpendReader(),
        Clock.System,
        logger = JsonLogger(LogLevel.ERROR, sink = {}),
    )

    @Test
    fun twoReplicasNeverFillAnAccountPastItsConcurrencyTogether() = serverTest(modules, overrides) { server ->
        with(server) {
            val shared = shareAs("member-a").body<SharedAccountResponse>()
            val replicas = listOf(koin.get<SharedAccountPool>(), replicaPool())
            val results = coroutineScope {
                List(10) { index ->
                    async {
                        replicas[index % 2].lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null)
                    }
                }.awaitAll()
            }
            val leases = results.filterIsInstance<LeaseResult.Leased>().map { it.lease }
            assertEquals(4, leases.size, "maxConcurrent 4, whichever replica each request reached")
            val account = Uuid.parse(shared.id)
            assertEquals(mapOf(account to 4), replicas[1].inFlight(Sharing.orgA), "Both replicas count every slot")
            val listed = client.get(base) { bearerAuth("admin-a") }.body<SharedAccountsResponse>().items.single()
            assertEquals(4, listed.inFlight)
            leases.forEach { it.release() }
            assertEquals(emptyMap(), replicas[0].inFlight(Sharing.orgA))

            // A replica killed with its slot: the slot frees itself at the end of its lease, by the database's clock.
            val orphan = assertIs<LeaseResult.Leased>(
                replicas[1].lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
            ).lease
            assertEquals(mapOf(account to 1), replicas[0].inFlight(Sharing.orgA))
            // A request still open renews its slot for a whole lease, by the database's clock.
            val database = koin.get<JdbcDatabase>()
            val held = database.inOrg(Sharing.orgA) { SharingSlots.select(SharingSlots.id).single()[SharingSlots.id] }
            database.inOrg(Sharing.orgA) { SharingSlots.update { it[expiresAt] = databaseNowPlus(1.seconds) } }
            AccountSlotRepositoryImpl(koin.get()).renew(Sharing.orgA, AccountSlot(held, account))
            val renewed = database.inOrg(Sharing.orgA) {
                SharingSlots.select(SharingSlots.id)
                    .where { SharingSlots.expiresAt greater databaseNowPlus(14.minutes) }.toList().size
            }
            assertEquals(1, renewed)
            database.inOrg(Sharing.orgA) { SharingSlots.update { it[expiresAt] = databaseNowPlus((-1).seconds) } }
            assertEquals(emptyMap(), replicas[0].inFlight(Sharing.orgA))
            assertEquals(account, orphan.account.id)
            repeat(4) {
                assertIs<LeaseResult.Leased>(
                    replicas[0].lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
                )
            }
            val rows = database.inOrg(Sharing.orgA) { SharingSlots.selectAll().toList().size }
            assertEquals(4, rows, "The expired slot was deleted by the next take on its account")
            orphan.release()
            assertEquals(mapOf(account to 4), replicas[0].inFlight(Sharing.orgA), "A late release changes nothing")
        }
    }

    @Test
    fun aSessionFindsItsAccountOnAnyReplicaAndOnlyItsDigestIsStored() = serverTest(modules, overrides) { server ->
        with(server) {
            val first = shareAs("member-a").body<SharedAccountResponse>()
            val second = shareAs("member-a", id = "acct-uuid-2", label = "Second").body<SharedAccountResponse>()
            val replicas = listOf(koin.get<SharedAccountPool>(), replicaPool())
            suspend fun lease(replica: Int, session: String?, member: Uuid = Sharing.memberA.memberId): String {
                val result = replicas[replica].lease(Sharing.orgA, member, AccountProvider.CLAUDE, session)
                val leased = assertIs<LeaseResult.Leased>(result).lease
                leased.release()
                return leased.account.id.toString()
            }
            val placed = lease(0, "session-1")
            val other = if (placed == first.id) second.id else first.id
            patchAs("admin-a", "$base/$other", UpdateAccountRequest(priority = 50))
            assertEquals(other, lease(1, null), "A new request goes to the higher priority")
            assertEquals(placed, lease(1, "session-1"), "The session stays on its account on the other replica")
            assertEquals(other, lease(1, "session-1", Sharing.otherA.memberId), "Another member's id is theirs")

            val database = koin.get<JdbcDatabase>()
            val stored = database.inOrg(Sharing.orgA) {
                SharingAffinities.select(SharingAffinities.sessionHash).map { it[SharingAffinities.sessionHash] }
            }
            assertEquals(2, stored.size)
            assertTrue(stored.all { it.length == 64 && "session" !in it }, "A SHA-256 digest, never the id")

            patchAs("admin-a", "$base/$placed", UpdateAccountRequest(paused = true))
            assertEquals(other, lease(0, "session-1"), "An account that stopped serving lets its sessions go")
            patchAs("admin-a", "$base/$placed", UpdateAccountRequest(paused = false))
            assertEquals(other, lease(1, "session-1"), "Once moved, a session stays on its new account")

            for (table in listOf<Table>(SharingSlots, SharingAffinities)) {
                val seen = database.inOrg(Sharing.orgB) { table.selectAll().toList().size }
                assertEquals(0, seen, "No leak: ${table.tableName}")
                assertEquals(
                    0,
                    database.inOrg(Sharing.orgB) { table.deleteAll() },
                    "No change: ${table.tableName}",
                )
            }
            val kept = database.inOrg(Sharing.orgA) { SharingAffinities.selectAll().toList().size }
            assertEquals(2, kept, "Org A's sessions are untouched")
        }
    }

    @Test
    fun aSessionIsRememberedOnlyWithinTheMembersAndTheOrganizationsBounds() =
        serverTest(modules, overrides) { server ->
            with(server) {
                val account = shareAs("member-a").body<SharedAccountResponse>().id
                val pool = koin.get<SharedAccountPool>()
                suspend fun lease(session: String, member: Uuid = Sharing.memberA.memberId) {
                    val result = pool.lease(Sharing.orgA, member, AccountProvider.CLAUDE, session)
                    assertIs<LeaseResult.Leased>(result).lease.release()
                }
                suspend fun remembered(session: String): Boolean = koin.get<JdbcDatabase>().inOrg(Sharing.orgA) {
                    SharingAffinities.selectAll().where { SharingAffinities.sessionHash eq sessionHash(session) }
                        .toList().isNotEmpty()
                }
                // Live sessions written as the module writes them, `count` of them for [member].
                suspend fun fill(member: Uuid, count: Int) {
                    koin.get<JdbcDatabase>().inOrg(Sharing.orgA) {
                        exec(
                            "INSERT INTO sharing_affinities (org_id, member_id, session_hash, account_id, " +
                                "expires_at) SELECT org_id, ?, md5(? || i), id, now() + INTERVAL '1 hour' " +
                                "FROM sharing_accounts, generate_series(1, ?) AS i WHERE id = ?",
                            listOf(
                                UuidColumnType() to member,
                                TextColumnType() to member.toString(),
                                IntegerColumnType() to count,
                                UuidColumnType() to Uuid.parse(account),
                            ),
                        )
                    }
                }

                lease("known")
                assertTrue(remembered("known"))
                fill(Sharing.memberA.memberId, AccountSlotRepository.MAX_MEMBER_AFFINITIES - 1)
                lease("past-the-member")
                assertFalse(remembered("past-the-member"), "A member at their bound starts no new session")
                lease("known")
                assertTrue(remembered("known"), "A session already remembered is kept")

                koin.get<JdbcDatabase>().inOrg(Sharing.orgA) { SharingAffinities.deleteAll() }
                fill(Sharing.otherA.memberId, AccountSlotRepository.MAX_ORG_AFFINITIES)
                lease("past-the-organization")
                assertFalse(remembered("past-the-organization"), "Nor a member of an organization at its bound")

                // Another organization's account, or none: nothing is written, nothing deleted.
                val windows = koin.get<QuotaWindowRepository>()
                val window = QuotaWindow("five_hour", 0.5, null, Clock.System.now())
                windows.put(Sharing.orgB, Uuid.parse(account), listOf(window))
                assertTrue(windows.byAccount(Sharing.orgA).isEmpty() && windows.byAccount(Sharing.orgB).isEmpty())
                assertFalse(koin.get<SharedAccountRepository>().delete(Sharing.orgA, Uuid.random()))
            }
        }
}
