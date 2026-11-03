// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches

import dev.ostrom.server.launches.domain.models.LaunchNotFoundException
import dev.ostrom.server.launches.domain.usecases.EndLaunchUseCaseImpl
import dev.ostrom.server.launches.fakes.RecordingTokens
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EndLaunchTest {
    @Test
    fun aLaunchsOwnAgentTokenRevokesItsTokens() = runTest {
        val tokens = RecordingTokens()
        EndLaunchUseCaseImpl(tokens)("lch_1", "lch_1")
        assertEquals(listOf("lch_1"), tokens.revoked)
    }

    @Test
    fun anyOtherCallerFindsNoLaunchAndRevokesNothing() = runTest {
        val tokens = RecordingTokens()
        for (caller in listOf("lch_2", null)) {
            assertFailsWith<LaunchNotFoundException> { EndLaunchUseCaseImpl(tokens)(caller, "lch_1") }
        }
        assertEquals(emptyList(), tokens.revoked)
    }
}
