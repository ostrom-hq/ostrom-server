// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.database.databaseNowPlus
import run.ostrom.server.sharing.infrastructure.SharingAffinities
import org.jetbrains.exposed.v1.jdbc.update
import run.ostrom.server.sharing.domain.AccountSlotRepository
import run.ostrom.server.sharing.domain.QuotaHistoryRepository
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.SharedAccountRepository
import run.ostrom.server.sharing.domain.SharingOrgs
import run.ostrom.server.sharing.presentation.SharedAccountResponse
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The capacity controller's reads on real PostgreSQL: the orgs that share accounts, listed across organizations by the
 * router's function; an org's readings of the last hour; and the sessions leased recently, each confined to its org.
 */
class PacingReadsPostgresTest : SharingPostgresHarness() {
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private suspend fun TestServer.shared(token: String = "admin-a", id: String = "acct-uuid-1"): Uuid =
        Uuid.parse(shareAs(token, id).body<SharedAccountResponse>().id)

    @Test
    fun theControllerReadsTheOrgsWithAccountsTheirReadingsAndSessions() = serverTest(modules, overrides) { server ->
        with(server) {
            assertEquals(emptyList(), koin.get<SharingOrgs>().withAccounts())
            val account = shared()
            assertEquals(listOf(Sharing.orgA), koin.get<SharingOrgs>().withAccounts())

            val history = koin.get<QuotaHistoryRepository>()
            history.record(
                Sharing.orgA,
                account,
                listOf(
                    QuotaWindow("five_hour", .2, now + 1.hours, now - 2.minutes),
                    QuotaWindow("five_hour", .3, now + 1.hours, now),
                    QuotaWindow("seven_day", .1, now + 5.hours, now - 2.hours),
                ),
            )
            val recent = history.recentOfOrg(Sharing.orgA, (now - 1.hours)..now, 10)
            assertEquals(listOf(.3, .2), recent.getValue(account).map { it.utilization })
            assertEquals(1, history.recentOfOrg(Sharing.orgA, (now - 1.hours)..now, 1).getValue(account).size)
            assertTrue(history.recentOfOrg(Sharing.orgB, (now - 1.hours)..now, 10).isEmpty())

            val slots = koin.get<AccountSlotRepository>()
            val candidate = koin.get<SharedAccountRepository>().get(Sharing.orgA, account)!!
            slots.take(Sharing.orgA, Sharing.memberA.memberId, "session-1", listOf(candidate)) { candidate }
            val sessions = slots.activeSessions(Sharing.orgA, 2.minutes)
            assertEquals(listOf(Sharing.memberA.memberId to account), sessions.map { it.memberId to it.accountId })
            assertEquals(64, sessions.single().sessionHash.length, "The digest, never the id")
            assertTrue(slots.activeSessions(Sharing.orgB, 2.minutes).isEmpty())
            database.superuserJdbc.query { SharingAffinities.update { it[expiresAt] = databaseNowPlus(50.minutes) } }
            assertTrue(slots.activeSessions(Sharing.orgA, 2.minutes).isEmpty(), "Leased ten minutes ago")
        }
    }
}
