// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage

import run.ostrom.server.usage.domain.InvalidUsageReconciliationException
import run.ostrom.server.usage.domain.ReconciledUsage
import run.ostrom.server.usage.domain.UsageEvidence
import run.ostrom.server.usage.domain.UsageReconciliationChange
import run.ostrom.server.usage.domain.UsageReconciliationRules
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The bounds of a correction (TeamCode's UsageReconciliationTest), and what makes a retry the same correction. */
class UsageReconciliationTest {
    private val change = UsageReconciliationChange(
        100,
        40,
        UsageEvidence.PROVIDER_RECORD,
        "Provider record",
        "Terminal usage verified",
        "req_fixture",
    )

    @Test
    fun verifiedTotalsAndEvidenceAreBounded() {
        assertEquals(change, UsageReconciliationRules.validate(change.copy(reference = " Provider record ")))
        assertEquals(0, UsageReconciliationRules.validate(change.copy(micros = 0)).micros)
        val top = UsageReconciliationRules.MAX_MICROS
        assertEquals(top, UsageReconciliationRules.validate(change.copy(micros = top, expectedMicros = top)).micros)
        val unseen = change.copy(expectedProviderRequestId = null)
        assertEquals(unseen, UsageReconciliationRules.validate(unseen))
        val bad = listOf(
            change.copy(expectedMicros = -1),
            change.copy(expectedMicros = top + 1),
            change.copy(micros = -1),
            change.copy(micros = top + 1),
            change.copy(reference = " "),
            change.copy(reason = "a\nsecond line"),
            change.copy(reason = "a\u007f"),
            change.copy(reason = "a".repeat(UsageReconciliationRules.MAX_TEXT + 1)),
            change.copy(expectedProviderRequestId = "https://untrusted.example/credential"),
        )
        for (each in bad) {
            assertFailsWith<InvalidUsageReconciliationException>(each.toString()) {
                UsageReconciliationRules.validate(each)
            }
        }
    }

    @Test
    fun providerRequestIdsAreBoundedOpaqueTokens() {
        for (id in listOf("req_fixture", "abc-123.4:5", "A".repeat(128))) {
            assertTrue(UsageReconciliationRules.providerRequestId(id), id)
        }
        for (id in listOf("", "x".repeat(129), "space secret", "bad\r\nheader", "https://example.org/", "../escape")) {
            assertFalse(UsageReconciliationRules.providerRequestId(id), id)
        }
    }

    @Test
    fun aRetryIsTheSameCorrectionOnlyWhenEveryFieldMatches() {
        val stored = ReconciledUsage(
            id = Uuid.random(),
            memberId = Uuid.random(),
            workflowId = null,
            launchId = null,
            providerRequestId = "req_fixture",
            heldAt = TestCallers.epoch,
            previousMicros = 100,
            micros = 40,
            source = UsageEvidence.PROVIDER_RECORD,
            reference = "Provider record",
            reason = "Terminal usage verified",
            reconciledBy = Uuid.random(),
            reconciledAt = TestCallers.epoch,
        )
        assertTrue(UsageReconciliationRules.sameCorrection(stored, change))
        val others = listOf(
            change.copy(expectedMicros = 99),
            change.copy(micros = 41),
            change.copy(source = UsageEvidence.PROVIDER_CONFIRMATION),
            change.copy(reference = "Other"),
            change.copy(reason = "Other"),
            change.copy(expectedProviderRequestId = null),
        )
        for (other in others) assertFalse(UsageReconciliationRules.sameCorrection(stored, other), other.toString())
    }

    @Test
    fun withoutTheOrgsModuleNoLoginIsKnown() = runTest {
        assertEquals(emptyMap(), NoLogins.idsOf(TestCallers.orgA, listOf("member")))
        assertEquals(emptyMap(), NoLogins.loginsOf(TestCallers.orgA, listOf(TestCallers.memberA.memberId)))
    }
}
