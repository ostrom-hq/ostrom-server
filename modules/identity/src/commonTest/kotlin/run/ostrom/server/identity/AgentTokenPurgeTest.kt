// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity

import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** The hourly purge of the agent tokens long expired, which the identity module starts with the server. */
class AgentTokenPurgeTest {
    private val now = Instant.parse("2026-11-03T12:00:00Z")

    @Test
    fun theAgentTokensExpiredAWeekAgoArePurgedEveryHourAndAFailedPurgeIsLoggedNotFatal() = runTest {
        val purged = mutableListOf<Instant>()
        val tokens = object : ApiTokensRepository by InMemoryApiTokensRepository() {
            override suspend fun purgeAgentTokens(before: Instant): Long {
                purged += before
                if (purged.size == 1) error("database down")
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
        val clock = object : Clock {
            override fun now(): Instant = now
        }
        val app = koinApplication {
            modules(
                module {
                    single<ApiTokensRepository> { tokens }
                    single<Clock> { clock }
                    single<Logger> { logger }
                },
            )
        }
        IdentityModule.start(backgroundScope, app.koin)
        runCurrent()
        advanceTimeBy(1.hours + 1.milliseconds)
        assertEquals(2, purged.size, "Tried again an hour after a failure")
        assertEquals(listOf("agent token purge failed"), warnings)
        assertEquals(now - 7.days, purged.first())
        assertEquals(IdentityModule.AGENT_TOKEN_RETENTION, 7.days)
        app.close()
    }
}
