// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.presentation.follow
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse

/** A stream whose reader left ends quietly: nobody is left to answer. */
class CloudConversationStreamTest {
    @Test
    fun aClosedStreamEndsWithoutAnError() = runTest {
        val channel = ByteChannel()
        channel.flushAndClose()
        var followed = false
        channel.follow { followed = true }
        assertFalse(followed, "The first frame already found the stream closed")
    }
}
