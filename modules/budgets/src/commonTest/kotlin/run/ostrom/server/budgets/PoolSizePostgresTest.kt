// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.AccountPoolShare
import run.ostrom.server.budgets.domain.AutomaticDaily
import run.ostrom.server.budgets.domain.BudgetAction
import run.ostrom.server.budgets.domain.BudgetLimit
import run.ostrom.server.budgets.domain.BudgetTarget
import run.ostrom.server.budgets.domain.BudgetWindow
import run.ostrom.server.budgets.domain.BudgetsRepository
import run.ostrom.server.budgets.domain.PoolContribution
import run.ostrom.server.budgets.domain.PoolSizeReport
import run.ostrom.server.budgets.domain.PoolSizeRepository
import run.ostrom.server.budgets.domain.PoolSizingService
import run.ostrom.server.budgets.domain.PoolSizingServiceImpl
import run.ostrom.server.budgets.domain.PoolSizingSources
import run.ostrom.server.budgets.domain.PricedCost
import run.ostrom.server.budgets.presentation.PoolSizeResponse
import run.ostrom.server.gateway.GatewayModule
import run.ostrom.server.gateway.domain.OrgPrices
import run.ostrom.server.gateway.domain.PacingSettingsRepository
import run.ostrom.server.gateway.domain.SpendGuard
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.AdminRequiredException
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.SharingModule
import run.ostrom.server.sharing.domain.QuotaHistoryRepository
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.QuotaWindowRepository
import run.ostrom.server.sharing.domain.SharedAccountRepository
import run.ostrom.server.sharing.domain.SharingOrgs
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.UsageModule
import run.ostrom.server.usage.domain.UsageEvent
import run.ostrom.server.usage.domain.UsageKind
import run.ostrom.server.usage.domain.UsageRepository
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Real PostgreSQL and HTTP: TeamCode's `AutomaticPoolSizePersistenceTest` and `PoolSizeRoutesTest`. The job stores a
 * value next to the typed budget, the limits read it only while the setting is on and the value is fresh, the typed
 * budget never changes, and the route answers the org's owners and admins signed in or a `team:read` key.
 */
class PoolSizePostgresTest {
    private val clock = TestClock()
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    }
    private val modules = listOf(SecretsModule, UsageModule, SharingModule, GatewayModule, BudgetsModule)
    private val org = TestCallers.orgA
    private val member = TestCallers.memberA.memberId
    private val now = TestCallers.epoch
    private val hour = 1.hours.inWholeMilliseconds

    // Four readings at hour boundaries: each hour settles 200,000 input tokens of Sonnet 4.5 ($0.60 at $3 a million)
    // and moves the weekly window by 6 points (full window $10) and the five-hour window by 12 (full window $5).
    private val base = Instant.fromEpochMilliseconds((now.toEpochMilliseconds() / hour - 4) * hour)
    private val weeklyReset = now + 3.days + 12.hours
    private val fiveHourReset = now + 2.hours

    private suspend fun TestServer.seed(): Uuid {
        val accounts = koin.get<SharedAccountRepository>()
        val account = accounts.upsert(sharedAccount("pool", now, orgId = org), "sealed").id
        val history = (0..3).flatMap { i ->
            listOf(
                QuotaWindow("seven_day", .10 + .06 * i, weeklyReset, base + i.hours),
                QuotaWindow("five_hour", .10 + .12 * i, fiveHourReset, base + i.hours),
            )
        }
        koin.get<QuotaHistoryRepository>().record(org, account, history)
        koin.get<QuotaWindowRepository>().put(
            org,
            account,
            listOf(QuotaWindow("seven_day", .28, weeklyReset, now), QuotaWindow("five_hour", .46, fiveHourReset, now)),
        )
        val events = (0..2).map { i ->
            UsageEvent(
                Uuid.random(), org, member, UsageKind.MODEL, "claude-sonnet-4-5",
                provider = "claude", accountId = account, inputTokens = 200_000, costMicros = 600_000, at = base + i.hours + 1.minutes,
            )
        }
        koin.get<UsageRepository>().insert(org, events)
        // An account of B with no reading: B has nothing to size.
        accounts.upsert(sharedAccount("other", now, orgId = TestCallers.orgB), "sealed")
        return account
    }

    private suspend fun TestServer.size(token: String?): HttpResponse =
        client.get("/api/v1/pools/size") { token?.let { bearerAuth(it) } }

    private suspend fun BudgetsRepository.orgDaily() = limits(org, member).org.filter { it.window == BudgetWindow.DAY }

    /** The seeded account's learned windows, estimate, mix and split, as TeamCode's persistence test reads them. */
    private fun assertLearned(report: PoolSizeReport, account: Uuid): AccountPoolShare {
        val share = report.estimate.accounts.single()
        assertEquals(PoolContribution.COUNTED, share.status, "Learned windows: ${report.capacities}")
        assertEquals(
            mapOf("five_hour" to 5_000_000L, "seven_day" to 10_000_000L),
            report.capacities.getValue(account).associate { it.window to it.fullWindowMicros },
        )
        // Weekly: 0.67 × $10 / 3.5 days; five-hour: $5 × 0.95 × 24 / 5. The weekly term binds.
        assertEquals((.67 * 10_000_000 / 3.5).roundToLong(), share.dailyMicros)
        assertEquals("seven_day", share.binding)
        // Capacity whatever is used: min($5 × 24 / 5, $10 / 7) a day, and in tokens at the mix (600,000 for $1.80).
        assertEquals(1_428_571, share.capacityDailyMicros)
        assertEquals(1_428_571, report.estimate.capacityDailyMicros)
        assertEquals(476_190, report.capacityDailyTokens)
        assertEquals(PricedCost(inputMicros = 1_800_000), report.mix.models.single().cost)
        assertEquals("anthropic", report.mix.models.single().provider)
        assertTrue(report.unpricedModels.isEmpty())
        // The weekly window opened 3.5 days ago and read 28 %; Ostrom's $1.80 explains 18 points, so 10 were used
        // elsewhere.
        val weekly = report.usage.getValue(account).single { it.window == "seven_day" }
        assertEquals(weeklyReset - 7.days, weekly.since)
        assertEquals(1_800_000, weekly.ostromMicros)
        assertEquals(.10, assertNotNull(weekly.outsideShare), 1e-9)
        assertEquals(1_000_000, weekly.outsideMicros)
        assertEquals(7_200_000, weekly.remainingMicros)
        assertEquals(1_000_000, report.outsideWeeklyMicros)
        return share
    }

    @Test
    fun theSettingMovesOnlyTheOrgsDailyLimitAndTheTypedBudgetIsKept() = serverTest(modules, overrides) { server ->
        val account = server.seed()
        val limits = server.koin.get<BudgetsRepository>()
        val sizing = server.koin.get<PoolSizingService>()
        val settings = server.koin.get<PacingSettingsRepository>()
        val store = server.koin.get<PoolSizeRepository>()
        val monthly = BudgetLimit(50_000_000)
        limits.replaceLimits(org, BudgetTarget.Org, listOf(BudgetLimit(2_000_000, BudgetWindow.DAY), monthly), now)
        assertEquals(listOf(BudgetLimit(2_000_000, BudgetWindow.DAY)), limits.orgDaily(), "Off: the typed budget")

        sizing.refresh()
        val report = sizing.report(TestCallers.adminA)
        assertFalse(report.enabled)
        assertFalse(report.usesAutomatic)
        assertEquals(2_000_000, report.manualDailyMicros)
        assertEquals(2_000_000, report.effectiveDailyMicros)
        val share = assertLearned(report, account)
        val automatic = assertNotNull(report.automatic)
        assertEquals(share.dailyMicros, automatic.micros, "The first value is the estimate")
        assertEquals(now, automatic.computedAt)
        assertEquals(listOf(BudgetLimit(2_000_000, BudgetWindow.DAY)), limits.orgDaily(), "Computed but off")

        settings.update(org, null, true)
        val onLimit = BudgetLimit(automatic.micros, BudgetWindow.DAY)
        assertEquals(listOf(onLimit), limits.orgDaily(), "On: the automatic value")
        assertEquals(monthly, limits.limits(org, member).org.single { it.window == BudgetWindow.MONTH }, "Monthly kept")
        val on = sizing.report(TestCallers.adminA)
        assertTrue(on.usesAutomatic)
        assertEquals(automatic.micros, on.effectiveDailyMicros)
        assertEquals(2_000_000, on.manualDailyMicros, "The typed budget is reported unchanged")

        // A typed daily budget that warns keeps warning at the automatic amount; none typed stops at it.
        val warn = BudgetLimit(2_000_000, BudgetWindow.DAY, BudgetAction.WARN)
        limits.replaceLimits(org, BudgetTarget.Org, listOf(warn), now)
        assertEquals(listOf(BudgetLimit(automatic.micros, BudgetWindow.DAY, BudgetAction.WARN)), limits.orgDaily())
        limits.replaceLimits(org, BudgetTarget.Org, listOf(monthly), now)
        assertEquals(listOf(BudgetLimit(automatic.micros, BudgetWindow.DAY)), limits.orgDaily())
        assertNull(sizing.report(TestCallers.adminA).manualDailyMicros)
        limits.replaceLimits(org, BudgetTarget.Org, listOf(BudgetLimit(2_000_000, BudgetWindow.DAY)), now)

        settings.update(org, null, false)
        assertEquals(listOf(BudgetLimit(2_000_000, BudgetWindow.DAY)), limits.orgDaily(), "Off again: the typed budget")
        assertEquals(2_000_000, limits.settings(org, 0).org.single().micros, "The typed budget was never rewritten")

        settings.update(org, null, true)
        clock.advance(1.hours + 1.minutes)
        assertEquals(listOf(BudgetLimit(2_000_000, BudgetWindow.DAY)), limits.orgDaily(), "Stale after an hour")

        // The next pass moves at most 20 % of the previous value an hour.
        store.store(org, clock.now - 1.hours) { 1_000_000 }
        sizing.refresh()
        assertEquals(1_200_000, assertNotNull(store.read(org)).micros)
        assertEquals(listOf(BudgetLimit(1_200_000, BudgetWindow.DAY)), limits.orgDaily())

        // Org B: its account has no reading, so nothing is stored and its limits are its own.
        assertNull(store.read(TestCallers.orgB))
        val other = sizing.report(TestCallers.adminB)
        assertEquals("no_estimate", other.estimate.reason)
        assertEquals(PoolContribution.NO_FRESH_OBSERVATION, other.estimate.accounts.single().status)
        assertTrue(limits.limits(TestCallers.orgB, TestCallers.adminB.memberId).org.isEmpty())

        assertFailsWith<AdminRequiredException> { sizing.report(TestCallers.memberA) }
    }

    @Test
    fun theControllersAllowanceFollowsTheGuard() = serverTest(modules, overrides) { server ->
        val limits = server.koin.get<BudgetsRepository>()
        val guard = server.koin.get<SpendGuard>()
        assertTrue(guard.available(org, member), "No limit")
        limits.replaceLimits(org, BudgetTarget.Org, listOf(BudgetLimit(0, BudgetWindow.DAY)), now)
        server.koin.get<UsageRepository>().insert(
            org,
            listOf(UsageEvent(Uuid.random(), org, member, UsageKind.MODEL, "m", costMicros = 1, at = now)),
        )
        assertFalse(guard.available(org, member), "A daily limit that stops, spent")
        assertTrue(guard.available(TestCallers.orgB, TestCallers.adminB.memberId), "Another org's")
    }

    @Test
    fun aFailingOrgLeavesTheOthersToRunAndTheFirstFailureIsThrown() = serverTest(modules, overrides) { server ->
        server.seed()
        val koin = server.koin
        val first = IllegalStateException("first")
        val failures = mutableListOf(first, IllegalStateException("second"))
        fun sizing(prices: OrgPrices, orgs: SharingOrgs = SharingOrgs { listOf(org, TestCallers.orgB) }) =
            PoolSizingServiceImpl(
                PoolSizingSources(koin.get(), koin.get(), koin.get(), koin.get(), prices),
                koin.get(), koin.get(), koin.get(), orgs, clock,
            )
        val thrown = assertFailsWith<IllegalStateException> {
            sizing(prices = { throw failures.removeFirst() }).refresh()
        }
        assertSame(first, thrown)
        assertTrue(failures.isEmpty(), "Both orgs ran")
        assertFailsWith<CancellationException> { sizing(prices = { throw CancellationException("stop") }).refresh() }
        assertNull(koin.get<PoolSizeRepository>().read(org))
    }

    @Test
    fun theRouteAnswersOwnersAndAdminsSignedInOrATeamReadKey() = serverTest(modules, overrides) { server ->
        val account = server.seed()
        server.koin.get<PoolSizingService>().refresh()
        val response = server.size("admin-a")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val body = response.body<PoolSizeResponse>()
        assertFalse(body.enabled)
        assertEquals("manual", body.source)
        assertNull(body.manualDailyMicros, "No typed daily budget")
        assertNull(body.effectiveDailyMicros)
        val automatic = assertNotNull(body.automaticDailyMicros)
        assertEquals(now.toString(), body.automaticComputedAt)
        assertEquals(automatic, body.estimatedDailyMicros)
        assertNull(body.reason)
        val shared = body.accounts.single()
        assertEquals(account.toString(), shared.accountId)
        assertEquals("counted", shared.status)
        assertEquals("seven_day", shared.binding)
        assertEquals(listOf("five_hour", "seven_day"), shared.windows.map { it.window })
        assertEquals(10_000_000, shared.windows.last().fullWindowMicros)
        assertEquals(3, shared.windows.last().samples)
        val weekly = shared.usage.single { it.window == "seven_day" }
        assertEquals(weeklyReset.toString(), weekly.resetsAt)
        assertEquals(.28, weekly.observedUtilization)
        assertEquals(1_800_000, weekly.ostromMicros)
        assertEquals(1_000_000, body.outsideWeeklyMicros)
        assertEquals(0, body.outsideUnknownAccounts)
        assertEquals(476_190, body.capacityDailyTokens)
        val model = body.models.single()
        assertEquals("claude-sonnet-4-5", model.model)
        assertEquals(600_000, model.inputTokens)
        assertEquals(1_800_000, model.totalMicros)
        assertEquals(0, model.outputMicros)

        server.koin.get<PacingSettingsRepository>().update(org, null, true)
        val on = server.size("login-a").body<PoolSizeResponse>()
        assertEquals("automatic", on.source, "The token of `ostrom login` reads it")
        assertEquals(automatic, on.effectiveDailyMicros)
        assertEquals(HttpStatusCode.OK, server.size("team-key-a").status, "A team:read key reads it")

        assertEquals(HttpStatusCode.Unauthorized, server.size(null).status)
        for (token in listOf("member-a", "cli-read", "key-a", "agent-a", "device-a", "team-key-member-a")) {
            assertEquals(HttpStatusCode.Forbidden, server.size(token).status, token)
        }
        // Another org's admin reads its own org, never A's accounts.
        val other = server.size("admin-b").body<PoolSizeResponse>()
        assertEquals("no_estimate", other.reason)
        assertFalse(other.accounts.any { it.accountId == account.toString() })
        assertNull(other.automaticDailyMicros)
    }

    @Test
    fun theStoredValueIsConfinedToItsOrg() = serverTest(modules, overrides) { server ->
        val store = server.koin.get<PoolSizeRepository>()
        assertEquals(AutomaticDaily(5, now), store.store(org, now) { previous -> if (previous == null) 5 else 0 })
        val next = store.store(org, now + 1.minutes) { assertNotNull(it).micros + 1 }
        assertEquals(AutomaticDaily(6, now + 1.minutes), next)
        assertNull(store.read(TestCallers.orgB))
        val rows = server.database.superuser.fetchAll("SELECT org_id FROM budgets_automatic_daily").getOrThrow()
        assertEquals(1, rows.size)
    }
}
