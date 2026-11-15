// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import run.ostrom.server.integrations.discord.domain.DiscordLinkDeniedException
import run.ostrom.server.integrations.discord.presentation.callbackOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The link's callback always sends the browser back, whatever happened. */
class DiscordRoutesTest {
    @Test
    fun theCallbacksOutcomeIsLinkedARefusalsTypeOrUnreachableForAnyOtherFailure() = runTest {
        assertEquals("linked", callbackOutcome { })
        assertEquals("discord_link_denied", callbackOutcome { throw DiscordLinkDeniedException() })
        assertEquals("discord_unreachable", callbackOutcome { error("The database is down") })
        assertFailsWith<CancellationException> { callbackOutcome { throw CancellationException("Stop") } }
    }
}
