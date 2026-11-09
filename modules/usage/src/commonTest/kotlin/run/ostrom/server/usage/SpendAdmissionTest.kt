// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage

import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.usage.domain.Admission
import run.ostrom.server.usage.domain.DailySpendLimits
import run.ostrom.server.usage.domain.SpendAdmissionImpl
import run.ostrom.server.usage.domain.SpendHold
import run.ostrom.server.usage.domain.SpendLimits
import run.ostrom.server.usage.domain.SpendScope
import run.ostrom.server.usage.domain.SpendTotals
import run.ostrom.server.usage.domain.UsageRecorderImpl
import run.ostrom.server.usage.infrastructure.UsageFeedImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** The rule that admits a model call against the limits, the holds it leaves, and the record that settles them. */
class SpendAdmissionTest {
    private val clock = TestClock()
    private val repository = InMemoryUsageRepository()
    private val admission = SpendAdmissionImpl(repository, clock)
    private val member = TestCallers.memberA.memberId
    private val since = TestCallers.epoch - 1.hours

    private fun hold(micros: Long, callId: Uuid = Uuid.random(), memberId: Uuid = member) =
        SpendHold(TestCallers.orgA, memberId, callId, micros)

    @Test
    fun aLimitIsReachedWhenSpentAndACallGoesOnlyWhenItsReserveFitsBesideTheOthers() {
        val org = SpendLimits(orgMicros = 10_000, memberMicros = null)
        assertEquals(Admission.Admitted, org.admit(SpendTotals(orgSpent = 6_000, orgHeld = 3_000), 1_000))
        assertEquals(
            Admission.Insufficient(SpendScope.ORG, 10_000, 1_000),
            org.admit(SpendTotals(orgSpent = 6_000, orgHeld = 3_000), 1_001),
        )
        assertEquals(
            Admission.Insufficient(SpendScope.ORG, 10_000, 0),
            org.admit(SpendTotals(orgSpent = 9_000, orgHeld = 3_000), 0),
            "Nothing is left once the holds pass the limit",
        )
        assertEquals(Admission.Exhausted(SpendScope.ORG, 10_000), org.admit(SpendTotals(orgSpent = 10_000), 0))

        val both = SpendLimits(orgMicros = 10_000, memberMicros = 2_000)
        assertEquals(
            Admission.Exhausted(SpendScope.MEMBER, 2_000),
            both.admit(SpendTotals(orgSpent = 9_999, memberSpent = 2_000), 5),
            "A reached limit answers before a short one",
        )
        assertEquals(
            Admission.Exhausted(SpendScope.ORG, 10_000),
            both.admit(SpendTotals(orgSpent = 10_000, memberSpent = 2_000), 5),
            "The org's first",
        )
        assertEquals(
            Admission.Insufficient(SpendScope.MEMBER, 2_000, 500),
            both.admit(SpendTotals(memberSpent = 1_000, memberHeld = 500), 501),
        )
        assertEquals(Admission.Admitted, both.admit(SpendTotals(memberSpent = 1_000, memberHeld = 500), 500))
        assertTrue(both.any)
        assertTrue(SpendLimits(null, 1).any)
        assertFalse(SpendLimits(null, null).any)
    }

    @Test
    fun withoutALimitNothingIsHeld() = runTest {
        assertEquals(Admission.Admitted, admission.admit(hold(5_000), SpendLimits(null, null), since))
        assertTrue(repository.holds.isEmpty())
    }

    @Test
    fun holdsCountUntilTheirCallIsRecordedOrTheyExpire() = runTest {
        val limits = SpendLimits(orgMicros = 10_000, memberMicros = null)
        val first = hold(6_000)
        assertEquals(Admission.Admitted, admission.admit(first, limits, since))
        assertEquals(clock.now() + 1.hours, repository.holds.getValue(first.callId).second)
        assertEquals(Admission.Insufficient(SpendScope.ORG, 10_000, 4_000), admission.admit(hold(5_000), limits, since))
        assertEquals(Admission.Admitted, admission.admit(first.copy(micros = 9_000), limits, since), "Asked again")

        val recorder = UsageRecorderImpl(repository, UsageFeedImpl(), RecordingLogger())
        recorder.settle(modelEvent(costMicros = 2_000).copy(id = first.callId))
        assertTrue(repository.holds.isEmpty(), "The recorded call drops its hold")
        assertEquals(Admission.Admitted, admission.admit(hold(8_000), limits, since))

        clock.advance(61.minutes)
        val again = admission.admit(hold(8_000), limits, since)
        assertEquals(Admission.Admitted, again, "An expired hold no longer counts")

        val shortLived = SpendAdmissionImpl(repository, clock, holdFor = 5.minutes)
        val brief = hold(1)
        assertEquals(Admission.Admitted, shortLived.admit(brief, SpendLimits(1_000_000, null), since))
        assertEquals(clock.now() + 5.minutes, repository.holds.getValue(brief.callId).second)
    }

    @Test
    fun aFailedSettleIsLoggedAndACancelledOnePropagates() = runTest {
        val logger = RecordingLogger()
        val recorder = UsageRecorderImpl(repository, UsageFeedImpl(), logger)
        repository.failure = IllegalStateException("database down")
        recorder.settle(modelEvent(costMicros = 1))
        assertEquals(listOf(LogLevel.WARN to "usage event not recorded"), logger.events)
        repository.failure = CancellationException("caller gone")
        assertFailsWith<CancellationException> { recorder.settle(modelEvent(costMicros = 1)) }
    }

    @Test
    fun aWorkflowsLimitsComeAfterTheMembersAndCountTheWorkflowsSumsOnly() {
        val today = TestCallers.epoch - 12.hours
        val workflow = SpendLimits(null, null, workflowMicros = 3_000)
        assertTrue(workflow.any)
        assertEquals(
            Admission.Admitted,
            workflow.admit(SpendTotals(orgSpent = 9_000, memberSpent = 9_000, workflowSpent = 2_000), 1_000),
        )
        assertEquals(
            Admission.Exhausted(SpendScope.WORKFLOW, 3_000),
            workflow.admit(SpendTotals(workflowSpent = 3_000), 0),
        )
        assertEquals(
            Admission.Insufficient(SpendScope.WORKFLOW, 3_000, 500),
            workflow.admit(SpendTotals(workflowSpent = 2_000, workflowHeld = 500, orgHeld = 9_000), 501),
            "Its own calls in flight count, not the org's",
        )
        val day = SpendLimits(null, null, DailySpendLimits(today, null, null, workflowMicros = 100))
        assertTrue(day.any)
        assertEquals(
            Admission.Exhausted(SpendScope.WORKFLOW, 100, daily = true),
            day.admit(SpendTotals(workflowDaySpent = 100), 0),
        )
        assertEquals(
            Admission.Insufficient(SpendScope.WORKFLOW, 100, 40, daily = true),
            day.admit(SpendTotals(workflowDaySpent = 50, workflowHeld = 10), 41),
        )
        val all = SpendLimits(10_000, 5_000, workflowMicros = 3_000)
        assertEquals(
            Admission.Exhausted(SpendScope.MEMBER, 5_000),
            all.admit(SpendTotals(memberSpent = 5_000, workflowSpent = 3_000), 0),
            "The member's before the workflow's",
        )
        assertEquals(
            Admission.Exhausted(SpendScope.WORKFLOW, 3_000),
            all.admit(SpendTotals(orgSpent = 9_999, workflowSpent = 3_000), 2),
            "A reached workflow answers before a short org",
        )
    }

    @Test
    fun aDailyLimitCountsTheDaysSpendAndTheCallsInFlightAfterTheMonths() {
        val today = TestCallers.epoch - 12.hours
        val orgDay = SpendLimits(null, null, DailySpendLimits(today, orgMicros = 1_000, memberMicros = null))
        assertTrue(orgDay.any)
        assertEquals(Admission.Admitted, orgDay.admit(SpendTotals(orgSpent = 9_000, orgDaySpent = 500), 500))
        assertEquals(
            Admission.Exhausted(SpendScope.ORG, 1_000, daily = true),
            orgDay.admit(SpendTotals(orgDaySpent = 1_000), 0),
        )
        assertEquals(
            Admission.Insufficient(SpendScope.ORG, 1_000, 200, daily = true),
            orgDay.admit(SpendTotals(orgDaySpent = 500, orgHeld = 300), 201),
            "A call in flight counts against the day",
        )

        val memberDay = SpendLimits(null, null, DailySpendLimits(today, orgMicros = null, memberMicros = 100))
        assertEquals(
            Admission.Exhausted(SpendScope.MEMBER, 100, daily = true),
            memberDay.admit(SpendTotals(memberDaySpent = 100), 0),
        )
        assertEquals(
            Admission.Insufficient(SpendScope.MEMBER, 100, 40, daily = true),
            memberDay.admit(SpendTotals(memberDaySpent = 50, memberHeld = 10), 41),
        )
        assertEquals(Admission.Admitted, memberDay.admit(SpendTotals(memberDaySpent = 50, memberHeld = 10), 40))

        val all = SpendLimits(10_000, 5_000, DailySpendLimits(today, orgMicros = 1_000, memberMicros = 100))
        assertEquals(
            Admission.Exhausted(SpendScope.ORG, 10_000),
            all.admit(SpendTotals(orgSpent = 10_000, orgDaySpent = 1_000), 0),
            "The month's before the day's",
        )
        assertEquals(
            Admission.Exhausted(SpendScope.ORG, 1_000, daily = true),
            all.admit(SpendTotals(orgDaySpent = 1_000, memberSpent = 5_000), 0),
            "The org's before the member's",
        )
        assertEquals(
            Admission.Exhausted(SpendScope.MEMBER, 5_000),
            all.admit(SpendTotals(memberSpent = 5_000, memberDaySpent = 100), 0),
        )
        assertEquals(
            Admission.Insufficient(SpendScope.ORG, 10_000, 1),
            all.admit(SpendTotals(orgSpent = 9_999, orgDaySpent = 999), 2),
            "A short month answers before a short day",
        )
    }

    @Test
    fun aDailyLimitIsAdmittedOnTheDaysSpend() = runTest {
        val yesterday = modelEvent(costMicros = 900, memberId = member, at = since - 1.hours)
        repository.insert(TestCallers.orgA, listOf(yesterday))
        val limits = SpendLimits(null, null, DailySpendLimits(since, orgMicros = null, memberMicros = 1_000))
        assertEquals(Admission.Admitted, admission.admit(hold(1_000), limits, since), "Before the day: not counted")
        assertEquals(
            Admission.Insufficient(SpendScope.MEMBER, 1_000, 0, daily = true),
            admission.admit(hold(1), limits, since),
            "The call admitted holds the whole day",
        )
    }
}
