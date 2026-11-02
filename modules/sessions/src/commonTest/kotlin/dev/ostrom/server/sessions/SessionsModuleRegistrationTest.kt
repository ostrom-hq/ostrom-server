// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions

import dev.ostrom.server.sessions.domain.usecases.SweepStaleSessionsUseCase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class SessionsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("sessions", SessionsModule.name)
        assertEquals(2, SessionsModule.migrations.size)
        koinApplication { modules(SessionsModule.koinModule) }.close()
    }

    @Test
    fun theSweepRunsEveryThirtySecondsAndOutlivesAFailedRun() = runTest {
        var runs = 0
        val sweep = object : SweepStaleSessionsUseCase {
            override suspend fun invoke(): List<String> {
                runs++
                check(runs != 1) { "first run fails" }
                return emptyList()
            }
        }
        val koin = koinApplication { modules(module { single<SweepStaleSessionsUseCase> { sweep } }) }.koin
        val failures = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, error -> failures += error }
        val scope = CoroutineScope(backgroundScope.coroutineContext + handler)

        SessionsModule.start(scope, koin)
        advanceTimeBy(29.seconds)
        runCurrent()
        assertEquals(0, runs)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(1, runs)
        assertEquals(listOf("first run fails"), failures.map { it.message })
        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(2, runs, "A failed run does not stop the sweep")
    }
}
