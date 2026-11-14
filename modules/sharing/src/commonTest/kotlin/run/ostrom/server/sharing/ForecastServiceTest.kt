// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.secrets.domain.AdminRequiredException
import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountNotFoundException
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.ForecastAccount
import run.ostrom.server.sharing.domain.ForecastServiceImpl
import run.ostrom.server.sharing.domain.NoQuotaHistory
import run.ostrom.server.sharing.domain.QuotaForecast
import run.ostrom.server.sharing.domain.QuotaHistoryRepository
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.RefreshOwnership
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.isRecordable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Readings in memory, as the database keeps them: newest first, at most the limit. */
class InMemoryQuotaHistory : QuotaHistoryRepository {
    val readings = mutableListOf<Triple<Uuid, Uuid, QuotaWindow>>()

    /** The calls of [recent]. */
    var reads = 0

    override suspend fun record(orgId: Uuid, accountId: Uuid, windows: List<QuotaWindow>) {
        windows.filter { it.isRecordable() }.forEach { readings += Triple(orgId, accountId, it) }
    }

    override suspend fun recent(
        orgId: Uuid,
        accountId: Uuid,
        windows: List<String>,
        observed: ClosedRange<Instant>,
        limit: Int,
    ): List<QuotaWindow> {
        reads++
        return readings.filter { (org, account, it) ->
            org == orgId && account == accountId && it.window in windows && it.observedAt in observed
        }.map { it.third }.sortedByDescending { it.observedAt }.take(limit)
    }

    override suspend fun prune(before: Instant, limit: Int): Long = 0
}

/** TeamCode's `ForecastPersistenceTest` cases that the service decides: bounds, selection, threshold, authority. */
class ForecastServiceTest {
    private val clock = TestClock()
    private val now get() = clock.now
    private val accounts = InMemoryAccounts()
    private val history = InMemoryQuotaHistory()
    private val service = ForecastServiceImpl(accounts, accounts, history, clock)

    private fun account(
        id: Int,
        org: Uuid = Sharing.orgA,
        ceiling: Double = AccountLimits.DEFAULT_CEILING,
        status: AccountStatus = AccountStatus.READY,
    ) = SharedAccount(
        id = Uuid.parse("00000000-0000-4000-8000-" + id.toString().padStart(12, '0')),
        orgId = org,
        provider = AccountProvider.CLAUDE,
        label = "Account $id",
        email = "a$id@example.com",
        providerAccountId = "provider-$id",
        ownerMemberId = null,
        // The list's order (priority) is not the forecasts' (id).
        priority = id,
        status = status,
        limits = AccountLimits(utilizationCeiling = ceiling),
        cooldownUntil = null,
        createdAt = now,
    ).also { accounts.accounts += it }

    private suspend fun readings(account: SharedAccount, window: String, vararg used: Double) {
        val readings = used.mapIndexed { index, u ->
            QuotaWindow(window, u, now + 1.hours, now - (used.size - 1 - index).minutes)
        }
        history.record(account.orgId, account.id, readings)
        accounts.put(account.orgId, account.id, listOf(readings.last()))
    }

    @Test
    fun anOrgWithoutAccountsHasNothingSelected() {
        runTest {
            val snapshot = service.load(Sharing.adminA)
            assertNull(snapshot.account)
            assertEquals(emptyList(), snapshot.accounts)
            assertFalse(snapshot.truncated)
            assertEquals(now, snapshot.generatedAt)
            assertEquals(AccountLimits.DEFAULT_CEILING, snapshot.pressureThreshold)
        }
    }

    @Test
    fun theFirstAccountByIdIsSelectedAndItsCeilingIsTheThreshold() {
        runTest {
            val second = account(2)
            val first = account(1, ceiling = .8, status = AccountStatus.DISABLED)
            account(3, Sharing.orgB)
            readings(first, "five_hour", .1, .2, .3)
            readings(first, "seven_day", .1, .1, .1)
            val snapshot = service.load(Sharing.adminA)
            assertEquals(ForecastAccount.of(first), snapshot.account)
            assertEquals(listOf(first.id, second.id), snapshot.accounts.map { it.id })
            assertEquals(.8, snapshot.pressureThreshold)
            val byWindow = snapshot.windows.associateBy { it.quota.window }
            assertEquals(listOf("five_hour", "seven_day"), snapshot.windows.map { it.quota.window })
            assertEquals(QuotaForecast.State.EXHAUSTS_FIRST, byWindow.getValue("five_hour").forecast.state)
            assertEquals(3, byWindow.getValue("five_hour").learningSamples)
            assertEquals(QuotaForecast.State.IDLE, byWindow.getValue("seven_day").forecast.state)
            assertEquals(0.0, byWindow.getValue("seven_day").forecast.pointsPerHour)
            assertFalse(snapshot.truncated)
        }
    }

    @Test
    fun anAccountWithoutWindowsReadsNoHistory() {
        runTest {
            val only = account(1)
            val snapshot = service.load(Sharing.adminA, only.id)
            assertEquals(emptyList(), snapshot.windows)
            assertEquals(0, history.reads)
        }
    }

    @Test
    fun anAccountOfAnotherOrgOrUnknownIsNotFound() {
        runTest {
            account(1)
            val foreign = account(2, Sharing.orgB)
            assertFailsWith<AccountNotFoundException> { service.load(Sharing.adminA, foreign.id) }
            assertFailsWith<AccountNotFoundException> { service.load(Sharing.adminA, Uuid.random()) }
            assertTrue(service.load(Sharing.adminA).accounts.none { it.id == foreign.id })
        }
    }

    @Test
    fun onlyAnOwnerOrAnAdminReadsTheForecasts() {
        runTest {
            account(1)
            assertFailsWith<AdminRequiredException> { service.load(Sharing.memberA) }
            assertEquals(Sharing.orgB, account(2, Sharing.orgB).orgId)
            assertEquals(1, service.load(Sharing.adminB).accounts.size, "An owner reads them too")
        }
    }

    @Test
    fun moreAccountsThanTheBoundAreTruncatedAndTheSelectedOneIsStillListed() {
        runTest {
            val all = (1..66).map { account(it) }
            val snapshot = service.load(Sharing.adminA, all.last().id)
            assertTrue(snapshot.truncated)
            assertEquals(65, snapshot.accounts.size)
            assertEquals(all.last().id, snapshot.accounts.last().id)
        }
    }

    @Test
    fun moreWindowsThanTheBoundAreTruncatedNeverSilentlyComplete() {
        runTest {
            val only = account(1)
            accounts.put(only.orgId, only.id, (1..130).map { QuotaWindow("window-$it", .1, now + 1.hours, now) })
            val snapshot = service.load(Sharing.adminA)
            assertTrue(snapshot.truncated)
            assertEquals(128, snapshot.windows.size)
        }
    }

    @Test
    fun moreReadingsThanTheBoundAreTruncated() {
        runTest {
            val only = account(1)
            val readings = (0..8192).map { QuotaWindow("five_hour", .1, now + 1.hours, now - 1.seconds * it / 10) }
            history.record(only.orgId, only.id, readings)
            accounts.put(only.orgId, only.id, listOf(readings.first()))
            val snapshot = service.load(Sharing.adminA)
            assertTrue(snapshot.truncated)
        }
    }

    @Test
    fun noHistoryRecordsNothing() {
        runTest {
            NoQuotaHistory.record(Sharing.orgA, Uuid.random(), listOf(QuotaWindow("five_hour", .1, null, now)))
            val none = NoQuotaHistory.recent(Sharing.orgA, Uuid.random(), listOf("five_hour"), now..now, 1)
            assertEquals(emptyList(), none)
            assertEquals(0L, NoQuotaHistory.prune(now, 1))
        }
    }

    @Test
    fun onlyAReadingThatCanBeOneIsRecordable() {
        val reading = QuotaWindow("five_hour", .5, now + 1.hours, now)
        assertTrue(reading.isRecordable())
        assertTrue(reading.copy(resetsAt = null).isRecordable())
        assertTrue(reading.copy(window = "w".repeat(80), utilization = 0.0).isRecordable())
        assertFalse(reading.copy(window = "").isRecordable())
        assertFalse(reading.copy(window = "w".repeat(81)).isRecordable())
        assertFalse(reading.copy(utilization = Double.NaN).isRecordable())
        assertFalse(reading.copy(utilization = -0.1).isRecordable())
        assertFalse(reading.copy(utilization = 1.1).isRecordable())
        assertFalse(reading.copy(resetsAt = now).isRecordable())
    }

    @Test
    fun theAccountsMetadataNamesWhoRenewsItsTokens() {
        val shared = account(1).copy(ownership = RefreshOwnership.ACCESS_ONLY)
        assertEquals(RefreshOwnership.ACCESS_ONLY, ForecastAccount.of(shared).ownership)
    }
}
