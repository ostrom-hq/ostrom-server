// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** `/ready` reads the pool's state: callers waiting a short while keep the replica ready, a long wait does not. */
class PoolHealthCheckTest {
    private var pool = PoolState(open = true, waiting = 0)
    private val clock = TestTimeSource()
    private val check = PoolHealthCheck({ pool }, clock, patience = 30.seconds)

    @Test
    fun anOpenPoolNobodyWaitsOnIsUp() {
        runTest {
            assertEquals("database", check.name)
            assertTrue(check.isUp())
        }
    }

    @Test
    fun aClosedPoolIsDown() {
        runTest {
            pool = PoolState(open = false, waiting = 0)
            assertFalse(check.isUp())
        }
    }

    @Test
    fun callersWaitingPastThePatienceMakeTheReplicaNotReadyUntilTheWaitEnds() {
        runTest {
            pool = PoolState(open = true, waiting = 3)
            assertTrue(check.isUp(), "a wait that starts is a slow answer, not an outage")
            clock += 29.seconds
            assertTrue(check.isUp())
            clock += 1.seconds
            assertFalse(check.isUp())
            pool = PoolState(open = true, waiting = 0)
            assertTrue(check.isUp())
            pool = PoolState(open = true, waiting = 1)
            clock += 60.seconds
            assertTrue(check.isUp(), "a new wait is measured from its own start")
        }
    }
}
