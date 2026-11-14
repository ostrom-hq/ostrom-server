// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/Relay.kt; see NOTICE.
package run.ostrom.server.push

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import run.ostrom.core.api.models.PushRelay

class ReplayGuardTest {
    @Test
    fun aSignatureIsNewOnlyTheFirstTime() = runTest {
        val guard = ReplayGuard()
        assertTrue(guard.first("a", 100, 100))
        assertFalse(guard.first("a", 100, 100))
        assertTrue(guard.first("b", 100, 100))
    }

    @Test
    fun aFullGuardDropsTheEntriesPastTheSkewWindowThenRefusesWhileFull() = runTest {
        val guard = ReplayGuard(maxEntries = 2)
        assertTrue(guard.first("a", 100, 100))
        assertTrue(guard.first("b", 100, 100))
        assertFalse(guard.first("c", 100, 100), "full of live entries: the request is not sent")
        assertTrue(guard.first("c", 100, 100 + 2 * PushRelay.MAX_SKEW_SECONDS + 1), "the old ones are dropped")
    }
}
