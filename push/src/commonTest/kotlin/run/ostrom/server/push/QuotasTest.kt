// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/Relay.kt; see NOTICE.
package run.ostrom.server.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class QuotasTest {
    @Test
    fun theTotalQuotaCountsEveryRequestOfTheMinute() = runTest {
        val quotas = Quotas(RelayLimits(perInstance = 10, perToken = 10, total = 2))
        assertTrue(quotas.take("i1", "t1", 5))
        assertTrue(quotas.take("i2", "t2", 5))
        assertFalse(quotas.take("i3", "t3", 5))
        assertTrue(quotas.take("i3", "t3", 6), "a new minute starts from zero")
    }

    @Test
    fun theInstanceAndTokenQuotasAreCountedSeparately() = runTest {
        val quotas = Quotas(RelayLimits(perInstance = 2, perToken = 1, total = 100))
        assertTrue(quotas.take("i1", "t1", 1))
        assertFalse(quotas.take("i1", "t1", 1), "the token's quota")
        assertTrue(quotas.take("i1", "t2", 1))
        assertFalse(quotas.take("i1", "t3", 1), "the instance's quota")
        assertTrue(quotas.take("i2", "t3", 1))
        assertTrue(quotas.take("i1", "t1", 2), "windows are per minute")
    }

    @Test
    fun aFullWindowRefusesNewKeysUntilItsMinuteIsOver() = runTest {
        val quotas = Quotas(RelayLimits(perInstance = 5, perToken = 5, total = 100), maxKeys = 1)
        assertTrue(quotas.take("i1", "t1", 1))
        assertFalse(quotas.take("i2", "t1", 1), "no room for a second instance")
        assertFalse(quotas.take("i1", "t2", 1), "no room for a second token")
        assertTrue(quotas.take("i1", "t1", 1), "a key already in the window keeps counting")
        assertTrue(quotas.take("i2", "t2", 2), "the minute over is dropped")
        assertEquals(false, quotas.take("i3", "t3", 2))
    }
}
