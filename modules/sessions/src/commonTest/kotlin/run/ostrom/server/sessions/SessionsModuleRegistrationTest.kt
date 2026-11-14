// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions

import run.ostrom.core.telemetry.Attributes
import run.ostrom.core.telemetry.metrics.MeterImpl
import run.ostrom.core.telemetry.metrics.MetricData
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.observability.CachedCount
import run.ostrom.server.kernel.observability.OstromMetrics
import run.ostrom.server.sessions.domain.services.NoSessionCosts
import run.ostrom.server.sessions.domain.services.SessionCosts
import run.ostrom.server.sessions.domain.usecases.SweepStaleSessionsUseCase
import run.ostrom.server.sessions.fakes.FakeCosts
import run.ostrom.server.sessions.fakes.RecordingLogger
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class SessionsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("sessions", SessionsModule.name)
        assertEquals(7, SessionsModule.migrations.size)
        koinApplication { modules(SessionsModule.koinModule) }.close()
    }

    @Test
    fun aSessionCostsWhatTheUsageModuleMeasuresAndNothingWithoutIt() {
        val measured = FakeCosts()
        val bound = koinApplication {
            modules(module { single<SessionCosts> { measured } }, module { single(named("costs")) { sessionCosts() } })
        }
        assertSame(measured, bound.koin.get<SessionCosts>(named("costs")))
        bound.close()
        val alone = koinApplication { modules(module { single(named("costs")) { sessionCosts() } }) }
        assertIs<NoSessionCosts>(alone.koin.get<SessionCosts>(named("costs")))
        alone.close()
    }

    @Test
    fun theSweepRunsAtStartThenEveryThirtySecondsAndOutlivesAFailedRun() = runTest {
        var runs = 0
        val sweep = object : SweepStaleSessionsUseCase {
            override suspend fun invoke(): List<String> {
                runs++
                check(runs != 1) { "first run fails" }
                return emptyList()
            }
        }
        val meter = MeterImpl(Clock.System)
        val logger = RecordingLogger()
        val koin = koinApplication {
            modules(
                module {
                    single<SweepStaleSessionsUseCase> { sweep }
                    single(named(SessionsModule.ACTIVE_SESSIONS)) { CachedCount { 3L } }
                    single { OstromMetrics(meter) }
                    single<Logger> { logger }
                },
            )
        }.koin
        SessionsModule.start(backgroundScope, koin)
        runCurrent()
        assertEquals(1, runs)
        assertEquals(listOf("stale session sweep failed"), logger.lines.map { it.first })
        advanceTimeBy(29.seconds)
        runCurrent()
        assertEquals(1, runs)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(2, runs, "A failed run does not stop the sweep")
        val active = (meter.collect().single { it.name == "ostrom.sessions.active" } as MetricData.Gauge).points
        assertEquals(mapOf(Attributes.of("where" to "local") to 3.0), active.associate { it.attributes to it.value })
    }
}
