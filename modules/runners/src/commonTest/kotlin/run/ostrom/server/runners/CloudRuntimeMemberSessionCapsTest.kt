// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeSource
import run.ostrom.server.runners.domain.NoMemberSessionCaps
import run.ostrom.server.runners.infrastructure.CloudRuntimeMemberSessionCaps
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CloudRuntimeMemberSessionCapsTest {
    private class Moving(var now: Instant = Instant.fromEpochSeconds(1_000)) : Clock {
        override fun now(): Instant = now
    }

    private class Source(var runtime: CloudRuntime) : CloudRuntimeSource {
        var reads = 0

        override suspend fun current(): CloudRuntime = runtime.also { reads++ }
    }

    @Test
    fun theCapsAreTheRuntimesAndZeroIsNoLimit() = runTest {
        val caps = CloudRuntimeMemberSessionCaps(Source(CloudRuntime()))
        assertEquals(2 to 20, caps.runningLimit() to caps.retainedLimit())
        val lifted = CloudRuntimeMemberSessionCaps(
            Source(CloudRuntime(maxRunningSessionsPerMember = 0, maxRetainedSessionsPerMember = 0)),
        )
        assertEquals(Int.MAX_VALUE to Int.MAX_VALUE, lifted.runningLimit() to lifted.retainedLimit())
        val none = NoMemberSessionCaps
        assertEquals(Int.MAX_VALUE to Int.MAX_VALUE, none.runningLimit() to none.retainedLimit())
    }

    @Test
    fun theRuntimeIsReadOnceEvery30Seconds() = runTest {
        val source = Source(CloudRuntime(maxRetainedSessionsPerMember = 3))
        val clock = Moving()
        val caps = CloudRuntimeMemberSessionCaps(source, clock)
        assertEquals(3, caps.retainedLimit())
        source.runtime = CloudRuntime(maxRetainedSessionsPerMember = 5)
        clock.now += 29.seconds
        assertEquals(3, caps.retainedLimit())
        assertEquals(2, caps.runningLimit())
        assertEquals(1, source.reads)
        clock.now += 1.seconds
        assertEquals(5, caps.retainedLimit())
        assertEquals(2, source.reads)
    }
}
