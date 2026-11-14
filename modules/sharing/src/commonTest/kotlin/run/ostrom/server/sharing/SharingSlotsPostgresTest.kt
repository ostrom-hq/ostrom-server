// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.logging.JsonLogger
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountRefreshRepository
import run.ostrom.server.sharing.domain.AccountSlot
import run.ostrom.server.sharing.domain.AccountTokenCipher
import run.ostrom.server.sharing.domain.AccountTokens
import run.ostrom.server.sharing.domain.LeaseResult
import run.ostrom.server.sharing.domain.NoAccountSpendReader
import run.ostrom.server.sharing.domain.SharedAccountPool
import run.ostrom.server.sharing.infrastructure.AccountSlotRepositoryImpl
import run.ostrom.server.sharing.presentation.SharedAccountResponse
import run.ostrom.server.sharing.presentation.SharedAccountsResponse
import run.ostrom.server.sharing.presentation.UpdateAccountRequest
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
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
        AccountSlotRepositoryImpl(driver),
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
            val held = driver.fetchInOrg(Sharing.orgA, Statement.create("SELECT id FROM sharing_slots"))
                .getOrThrow().single().get("id").asUuid()
            driver.executeInOrg(
                Sharing.orgA,
                Statement.create("UPDATE sharing_slots SET expires_at = now() + INTERVAL '1 second'"),
            ).getOrThrow()
            AccountSlotRepositoryImpl(driver).renew(Sharing.orgA, AccountSlot(held, account))
            val renewed = driver.fetchInOrg(
                Sharing.orgA,
                Statement.create("SELECT id FROM sharing_slots WHERE expires_at > now() + INTERVAL '14 minutes'"),
            ).getOrThrow().size
            assertEquals(1, renewed)
            driver.executeInOrg(
                Sharing.orgA,
                Statement.create("UPDATE sharing_slots SET expires_at = now() - INTERVAL '1 second'"),
            ).getOrThrow()
            assertEquals(emptyMap(), replicas[0].inFlight(Sharing.orgA))
            assertEquals(account, orphan.account.id)
            repeat(4) {
                assertIs<LeaseResult.Leased>(
                    replicas[0].lease(Sharing.orgA, Sharing.memberA.memberId, AccountProvider.CLAUDE, null),
                )
            }
            val rows = driver.fetchInOrg(Sharing.orgA, Statement.create("SELECT expires_at FROM sharing_slots"))
                .getOrThrow().size
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

            val hashes = Statement.create("SELECT session_hash FROM sharing_affinities")
            val stored = driver.fetchInOrg(Sharing.orgA, hashes).getOrThrow().map { it.get("session_hash").asString() }
            assertEquals(2, stored.size)
            assertTrue(stored.all { it.length == 64 && "session" !in it }, "A SHA-256 digest, never the id")

            patchAs("admin-a", "$base/$placed", UpdateAccountRequest(paused = true))
            assertEquals(other, lease(0, "session-1"), "An account that stopped serving lets its sessions go")
            patchAs("admin-a", "$base/$placed", UpdateAccountRequest(paused = false))
            assertEquals(other, lease(1, "session-1"), "Once moved, a session stays on its new account")

            for (table in listOf("sharing_slots", "sharing_affinities")) {
                val unfiltered = Statement.create("SELECT org_id FROM $table")
                assertEquals(0, driver.fetchInOrg(Sharing.orgB, unfiltered).getOrThrow().size, "No leak: $table")
                assertEquals(
                    0L,
                    driver.executeInOrg(Sharing.orgB, Statement.create("DELETE FROM $table")).getOrThrow(),
                    "No change: $table",
                )
            }
            val kept = driver.fetchInOrg(Sharing.orgA, Statement.create("SELECT 1 FROM sharing_affinities"))
            assertEquals(2, kept.getOrThrow().size, "Org A's sessions are untouched")
        }
    }
}
