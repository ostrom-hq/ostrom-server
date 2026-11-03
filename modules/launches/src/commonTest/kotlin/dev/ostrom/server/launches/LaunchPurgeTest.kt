// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.launches.domain.models.Launch
import dev.ostrom.server.launches.domain.models.LaunchLimit
import dev.ostrom.server.launches.domain.repositories.LaunchesRepository
import dev.ostrom.server.launches.fakes.FixedClock
import dev.ostrom.server.launches.fakes.InMemoryLaunches
import dev.ostrom.server.testing.serverTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The launches long expired are deleted every hour, across organizations, and nothing else. */
class LaunchPurgeTest {
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private fun launch(orgId: Uuid, expiresAt: Instant) = Launch(
        id = "lch_" + Uuid.random().toHexString(), orgId = orgId, userId = Uuid.random(), dev = "ada", repo = null,
        agent = "claude-code", model = null, createdAt = expiresAt - 1.days, expiresAt = expiresAt,
    )

    @Test
    fun thePurgeDeletesOnlyTheLaunchesExpiredBeforeItsDateOfEveryOrganization() = serverTest(launchModules()) { s ->
        val launches = s.koin.get<LaunchesRepository>()
        val orgA = Uuid.random()
        val orgB = Uuid.random()
        val roomy = LaunchLimit(window = 1.hours, perPerson = 100, perOrg = 100)
        val oldA = launch(orgA, now - 31.days)
        val oldB = launch(orgB, now - 40.days)
        val recent = launch(orgA, now - 29.days)
        val live = launch(orgB, now + 1.days)
        listOf(oldA, oldB, recent, live).forEach { assertNull(launches.create(it, roomy)) }

        assertEquals(2L, launches.purge(now - 30.days), "The two launches expired before the date")
        assertEquals(0L, launches.purge(now - 30.days), "Nothing left to purge")
        assertNull(launches.find(orgA, oldA.id))
        assertNull(launches.find(orgB, oldB.id))
        assertEquals(recent, launches.find(orgA, recent.id), "Expired since less than the retention: kept")
        assertEquals(live, launches.find(orgB, live.id))
    }

    @Test
    fun theLaunchesExpiredAMonthAgoArePurgedEveryHourAndAFailedPurgeIsLoggedNotFatal() = runTest {
        val purged = mutableListOf<Instant>()
        val launches = object : LaunchesRepository by InMemoryLaunches() {
            override suspend fun purge(before: Instant): Long {
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
        val clock = FixedClock()
        val app = koinApplication {
            modules(
                module {
                    single<LaunchesRepository> { launches }
                    single<Clock> { clock }
                    single<Logger> { logger }
                },
            )
        }
        LaunchesModule.start(backgroundScope, app.koin)
        runCurrent()
        advanceTimeBy(1.hours + 1.milliseconds)
        assertEquals(2, purged.size, "Tried again an hour after a failure")
        assertEquals(listOf("launch purge failed"), warnings)
        assertEquals(clock.now - 30.days, purged.first())
        assertEquals(30.days, LaunchesModule.LAUNCH_RETENTION)
        app.close()
    }
}
