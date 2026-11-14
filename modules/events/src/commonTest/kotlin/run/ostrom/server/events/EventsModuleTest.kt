// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import run.ostrom.server.events.domain.OrgEventsRepository
import run.ostrom.server.kernel.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** The module's workers start with the server and stop with it. */
class EventsModuleTest {
    @Test
    fun theStartedModulePurgesTheJournal() = runTest {
        val events = InMemoryEvents()
        val app = koinApplication {
            allowOverride(true)
            modules(
                EventsModule.koinModule,
                module {
                    single<OrgEventsRepository> { events }
                    single<Clock> { Clock.System }
                    single<Logger> { RecordingLogger() }
                },
            )
        }
        EventsModule.start(backgroundScope, app.koin)
        advanceTimeBy(1.seconds)
        assertEquals(1, events.purges.size)
        app.close()
    }
}
