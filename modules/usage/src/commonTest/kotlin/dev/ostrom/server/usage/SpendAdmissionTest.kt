// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage

import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.usage.domain.Admission
import dev.ostrom.server.usage.domain.SpendAdmissionImpl
import dev.ostrom.server.usage.domain.SpendHold
import dev.ostrom.server.usage.domain.SpendLimits
import dev.ostrom.server.usage.domain.SpendScope
import dev.ostrom.server.usage.domain.SpendTotals
import dev.ostrom.server.usage.domain.UsageRecorderImpl
import dev.ostrom.server.usage.infrastructure.UsageFeedImpl
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
}
