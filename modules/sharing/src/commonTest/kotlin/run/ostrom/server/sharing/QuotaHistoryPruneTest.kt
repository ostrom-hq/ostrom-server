// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.sharing.domain.QuotaHistoryRepository
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** The quota history is pruned each minute, 1,000 readings at most a pass; a failed pass is logged, never fatal. */
class QuotaHistoryPruneTest {
    @Test
    fun theReadingsOlderThanSevenDaysArePrunedEachMinuteAndAFailedPassIsLoggedNotFatal() = runTest {
        val pruned = mutableListOf<Pair<Instant, Int>>()
        val history = object : QuotaHistoryRepository by InMemoryQuotaHistory() {
            override suspend fun prune(before: Instant, limit: Int): Long {
                pruned += before to limit
                if (pruned.size == 1) error("database down")
                return 0
            }
        }
        val warnings = mutableListOf<String>()
        val logger = object : Logger {
            override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
                if (level == LogLevel.WARN) warnings += message
            }

            override fun with(fields: Map<String, Any?>): Logger = this
        }
        val clock = TestClock()
        val app = koinApplication {
            modules(
                module {
                    single<QuotaHistoryRepository> { history }
                    single<Clock> { clock }
                    single<Logger> { logger }
                },
            )
        }
        SharingModule.start(backgroundScope, app.koin)
        runCurrent()
        advanceTimeBy(1.minutes + 1.milliseconds)
        assertEquals(2, pruned.size, "Tried again a minute after a failure")
        assertEquals(listOf("quota history prune failed"), warnings)
        assertEquals(clock.now - 7.days to 1_000, pruned.first())
    }
}
