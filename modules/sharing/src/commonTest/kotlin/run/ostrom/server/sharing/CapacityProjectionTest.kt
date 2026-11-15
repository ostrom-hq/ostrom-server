// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.sharing.domain.AccountHeadroom
import run.ostrom.server.sharing.domain.AccountHeadroomUseCase
import run.ostrom.server.sharing.domain.AccountLimits
import run.ostrom.server.sharing.domain.AccountProvider
import run.ostrom.server.sharing.domain.AccountStatus
import run.ostrom.server.sharing.domain.AccountUnavailability
import run.ostrom.server.sharing.domain.CapacityGap
import run.ostrom.server.sharing.domain.PoolAccountObservation
import run.ostrom.server.sharing.domain.ProjectCapacityUseCase
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.RefreshOwnership
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.WindowHeadroom
import run.ostrom.server.sharing.domain.windowPeriod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** TeamCode's `AccountHeadroomTest` and `CapacityProjectionTest`, on Ostrom's accounts and their own ceilings. */
class CapacityProjectionTest {
    private val now = Instant.parse("2026-11-14T12:00:00Z")

    private fun account(
        status: AccountStatus = AccountStatus.READY,
        ownership: RefreshOwnership = RefreshOwnership.OSTROM,
        cooldownUntil: Instant? = null,
        ceiling: Double = .95,
    ) = SharedAccount(
        id = Uuid.random(),
        orgId = Uuid.random(),
        provider = AccountProvider.CLAUDE,
        label = "a",
        email = null,
        providerAccountId = "acct",
        ownerMemberId = null,
        priority = 0,
        status = status,
        limits = AccountLimits(utilizationCeiling = ceiling),
        cooldownUntil = cooldownUntil,
        createdAt = now,
        ownership = ownership,
    )

    private fun window(name: String, utilization: Double, resetsIn: Duration? = 1.hours) =
        QuotaWindow(name, utilization, resetsIn?.let { now + it }, now)

    private fun headroom(utilization: Double, resetsIn: Duration?, ceiling: Double = .95) = AccountHeadroom(
        account(ceiling = ceiling),
        listOf(WindowHeadroom("five_hour", utilization, ceiling - utilization, now, resetsIn?.let { now + it })),
    )

    @Test
    fun headroomKeepsOnlyServingAccountsAndTheirCurrentSharedWindows() {
        val use = AccountHeadroomUseCase()
        val ready = PoolAccountObservation(
            account(),
            listOf(
                window("five_hour", .5),
                window("model:opus", .99),
                QuotaWindow("seven_day", .2, now - 1.minutes, now - 2.hours),
                QuotaWindow("later", .1, null, now + 1.minutes),
            ),
        )
        val full = PoolAccountObservation(account(ceiling = .8), listOf(window("five_hour", .8)))
        val usable = use.usable(listOf(ready, full), now)
        assertEquals(listOf("five_hour"), usable.single().windows.map { it.window })
        assertEquals(.45, usable.single().window("five_hour")!!.remainingShare, 1e-9)
        assertNull(usable.single().window("seven_day"), "A reading whose reset passed is unknown, not full")
        val exhausted = use.exhausted(listOf(ready, full), now).single()
        assertEquals(0.0, exhausted.windows.single().remainingShare)
        assertEquals(.8, exhausted.ceiling)
    }

    @Test
    fun unavailabilityIsClassifiedOnce() {
        val off = AccountUnavailability.of(account(AccountStatus.DISABLED), false, now)
        assertEquals(AccountUnavailability.DISABLED, off)
        assertEquals(
            AccountUnavailability.PENDING_HANDOVER,
            AccountUnavailability.of(account(ownership = RefreshOwnership.PENDING_HANDOVER), false, now),
        )
        assertEquals(
            AccountUnavailability.RECONNECT_REQUIRED,
            AccountUnavailability.of(account(AccountStatus.WRONG_ACCOUNT), false, now),
        )
        assertEquals(
            AccountUnavailability.COOLING_DOWN,
            AccountUnavailability.of(account(cooldownUntil = now + 1.minutes), true, now),
        )
        assertEquals(AccountUnavailability.QUOTA_EXHAUSTED, AccountUnavailability.of(account(), true, now))
        assertNull(AccountUnavailability.of(account(cooldownUntil = now - 1.minutes), false, now))
        val disabled = PoolAccountObservation(account(AccountStatus.DISABLED), listOf(window("five_hour", .99)))
        assertEquals(emptyList(), AccountHeadroomUseCase().exhausted(listOf(disabled), now))
    }

    @Test
    fun theAffordableShareShrinksAsTheGapGetsLongerOrCloser() {
        assertEquals(1.0, CapacityGap(now, now).affordableShare(now))
        assertEquals(.5, CapacityGap(now + 2.hours, now + 4.hours).affordableShare(now), 1e-9)
        assertEquals(2.0 / 24, CapacityGap(now + 2.hours, now + 3.days).affordableShare(now), 1e-9)
        assertEquals(0.0, CapacityGap(now - 1.hours, now + 1.hours).affordableShare(now))
    }

    @Test
    fun windowPeriodsAreReadFromTheirNames() {
        assertEquals(5.hours, windowPeriod("five_hour"))
        assertEquals(7.days, windowPeriod("seven_day"))
        assertNull(windowPeriod("model:opus"))
    }

    @Test
    fun aGapAppearsOnlyWhenEveryAccountRunsOutBeforeAReset() {
        val project = ProjectCapacityUseCase()
        // 0.45 left at 0.9 per hour: empty in 30 minutes, back at the reset in one hour.
        val busy = mapOf("five_hour" to .9)
        val gap = assertNotNull(project.firstGap(listOf(headroom(.5, 1.hours)), emptyList(), busy, now))
        assertEquals(now + 30.minutes, gap.startsAt)
        assertEquals(now + 1.hours, gap.endsAt)
        val twoHours = ProjectCapacityUseCase(horizon = 2.hours)
        assertNull(twoHours.firstGap(listOf(headroom(.5, 1.hours)), emptyList(), mapOf("five_hour" to .3), now))
        assertNull(project.firstGap(listOf(headroom(.5, 1.hours)), emptyList(), emptyMap(), now), "Nothing burns")
        val two = listOf(headroom(.5, 1.hours), headroom(.5, 1.hours))
        val carried = twoHours.firstGap(two, emptyList(), mapOf("five_hour" to .6), now)
        assertNull(carried, "A second account carries the load")
    }

    @Test
    fun aPastResetOrARateThatIsNotARealBurnIsUnknown() {
        val project = ProjectCapacityUseCase()
        val past = project.firstGap(listOf(headroom(.5, (-1).hours)), emptyList(), mapOf("five_hour" to 1.0), now)
        assertEquals(CapacityGap(now + 27.minutes, now + 7.days), past, "A reset already gone is unknown")
        for (rate in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
            assertNull(project.firstGap(listOf(headroom(.5, 1.hours)), emptyList(), mapOf("five_hour" to rate), now))
        }
        // Two accounts without a known reset: each serves in turn, then the gap lasts to the horizon.
        val two = listOf(headroom(.5, null), headroom(.5, null))
        val both = assertNotNull(project.firstGap(two, emptyList(), mapOf("five_hour" to 1.0), now))
        assertEquals(now + 54.minutes, both.startsAt)
    }

    @Test
    fun aGapLastsUntilEveryWindowOfAnAccountResetsOrTheHorizon() {
        val project = ProjectCapacityUseCase()
        val empty = AccountHeadroom(
            account(),
            listOf(
                WindowHeadroom("five_hour", .95, 0.0, now, now + 1.hours),
                WindowHeadroom("seven_day", .95, 0.0, now, now + 3.hours),
            ),
        )
        val both = assertNotNull(project.firstGap(emptyList(), listOf(empty), emptyMap(), now))
        assertEquals(CapacityGap(now, now + 3.hours), both, "Serving again once its last window resets")
        val beyond = ProjectCapacityUseCase(horizon = 1.hours)
            .firstGap(listOf(headroom(.5, 2.hours)), emptyList(), mapOf("five_hour" to 1.0), now)
        assertEquals(CapacityGap(now + 27.minutes, now + 1.hours), beyond, "The reset comes after the horizon")
        val model = AccountHeadroom(account(), listOf(WindowHeadroom("model:opus", .95, 0.0, now, now + 10.minutes)))
        val unperiodic = project.firstGap(emptyList(), listOf(model), emptyMap(), now)
        assertEquals(CapacityGap(now, now + 10.minutes), unperiodic, "A window without a period resets once")
    }

    @Test
    fun anExhaustedAccountCountsAgainOnceItsWindowResets() {
        val project = ProjectCapacityUseCase()
        val exhausted = AccountHeadroom(
            account(),
            listOf(WindowHeadroom("five_hour", .95, 0.0, now, now + 10.minutes)),
        )
        val gap = ProjectCapacityUseCase(horizon = 1.hours)
            .firstGap(listOf(headroom(.85, 2.hours)), listOf(exhausted), mapOf("five_hour" to .6), now)
        assertNull(gap, "The exhausted account refills at its reset before the usable one runs out")
        val unknownReset = project.firstGap(listOf(headroom(.5, null)), emptyList(), mapOf("five_hour" to 1.0), now)
        assertEquals(now + 27.minutes, assertNotNull(unknownReset).startsAt)
        assertEquals(now + 7.days, unknownReset.endsAt, "Without a reset the gap lasts to the horizon")
        val none = project.firstGap(emptyList(), listOf(exhausted), mapOf("five_hour" to 1.0), now)
        assertEquals(CapacityGap(now, now + 10.minutes), none, "No usable account: the gap starts now")
        val short = ProjectCapacityUseCase(horizon = 10.minutes)
        assertNull(short.firstGap(listOf(headroom(.5, 1.hours)), emptyList(), mapOf("five_hour" to .1), now))
    }
}
