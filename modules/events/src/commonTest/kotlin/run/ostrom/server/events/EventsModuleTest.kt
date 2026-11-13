// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import run.ostrom.server.events.domain.DeliverWebhooksUseCase
import run.ostrom.server.events.domain.OrgEventsRepository
import run.ostrom.server.events.domain.WebhookSender
import run.ostrom.server.events.domain.WebhookTargets
import run.ostrom.server.events.infrastructure.HttpWebhookSender
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** The module's wiring: its workers start with the server, its webhooks go through the outbound client. */
class EventsModuleTest {
    private class Passes : DeliverWebhooksUseCase {
        var delivered = 0

        override suspend fun invoke(): Int = 0.also { delivered += 1 }

        override suspend fun maintain(): Int = 0
    }

    @Test
    fun theStartedModulePurgesTheJournalAndDeliversTheWebhooks() = runTest {
        val events = InMemoryEvents()
        val passes = Passes()
        val app = koinApplication {
            allowOverride(true)
            modules(
                EventsModule.koinModule,
                module {
                    single<OrgEventsRepository> { events }
                    single<DeliverWebhooksUseCase> { passes }
                    single<Clock> { Clock.System }
                    single<Logger> { RecordingLogger() }
                },
            )
        }
        EventsModule.start(backgroundScope, app.koin)
        advanceTimeBy(1.seconds + 1.seconds / 2)
        assertEquals(1, events.purges.size)
        assertEquals(2, passes.delivered)
        app.close()
    }

    @Test
    fun webhooksAreSentThroughTheOutboundClientUnderTheOperatorsPolicy() {
        for (allow in listOf(false, true)) {
            val app = koinApplication {
                modules(
                    EventsModule.koinModule,
                    module {
                        single<ConfigSource> {
                            MapConfigSource(mapOf("OSTROM_WEBHOOKS_ALLOW_PRIVATE_TARGETS" to allow.toString()))
                        }
                    },
                )
            }
            assertIs<HttpWebhookSender>(app.koin.get<WebhookSender>())
            assertEquals(allow, app.koin.get<WebhookTargets>().allowPrivate)
            app.close()
        }
    }
}
