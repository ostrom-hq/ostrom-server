// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.observability

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class CachedCountTest {
    @Test
    fun theCountIsReadAtOnceThenEveryThirtySecondsAndAFailedReadKeepsTheLastOne() = runTest {
        var reads = 0
        val count = CachedCount {
            reads++
            check(reads != 2) { "database down" }
            reads * 10L
        }
        val failures = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, error -> failures += error }
        val scope = CoroutineScope(backgroundScope.coroutineContext + handler)

        assertEquals(0, count.value(), "Nothing read yet")
        count.refreshEvery(scope)
        runCurrent()
        assertEquals(10, count.value())
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(10, count.value(), "A failed read keeps the last count")
        assertEquals(listOf("database down"), failures.map { it.message })
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(30, count.value(), "A failed read does not stop the loop")
        assertEquals(30.seconds, CachedCount.EVERY)
    }
}
