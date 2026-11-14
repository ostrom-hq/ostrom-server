// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.InferenceRefusal
import run.ostrom.server.gateway.domain.RefusalKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** TeamCode e7dbca54: an agent retries only what retrying may change, and only a return within two minutes. */
class RefusalRetryTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000)

    @Test
    fun onlyTheRefusalsRetryingMayChangeAreRetried() {
        val final = RefusalKind.entries.filterNot { it.retryable }.map { it.code }.toSet()
        assertEquals(
            setOf(
                "invalid", "unauthenticated", "credential_not_allowed", "model_not_allowed", "budget_exhausted",
                "budget_insufficient", "no_credential", "invalid_workload", "invalid_request_id",
            ),
            final,
        )
        assertFalse(InferenceRefusal(RefusalKind.NO_CREDENTIAL, "none").shouldRetry(now))
        assertTrue(InferenceRefusal(RefusalKind.QUEUE_TIMEOUT, "waited").shouldRetry(now))
    }

    @Test
    fun aReturnPastTwoMinutesIsNotRetried() {
        val busy = InferenceRefusal(RefusalKind.NO_CAPACITY, "busy")
        assertTrue(busy.copy(retryAt = now + 120.seconds).shouldRetry(now))
        assertFalse(busy.copy(retryAt = now + 121.seconds).shouldRetry(now))
        assertFalse(
            InferenceRefusal(RefusalKind.BUDGET_EXHAUSTED, "spent", retryAt = now + 1.minutes).shouldRetry(now),
        )
    }
}
