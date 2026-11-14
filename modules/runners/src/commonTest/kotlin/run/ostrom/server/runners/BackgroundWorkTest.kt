// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.BackgroundWork
import run.ostrom.server.runners.fakes.session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class BackgroundWorkTest {
    private val t0 = Instant.parse("2026-11-14T10:00:00Z")

    @Test
    fun theActivityIsTheLastReportAtMostTheCapAfterItsStretchBegan() {
        val working = session().copy(backgroundSince = t0, backgroundSeenAt = t0 + 30.minutes)
        assertEquals(t0 + 30.minutes, BackgroundWork.activity(working, 240))
        assertEquals(t0 + 10.minutes, BackgroundWork.activity(working, 10))
        assertNull(BackgroundWork.activity(working, 0))
        assertNull(BackgroundWork.activity(working.copy(backgroundSeenAt = null), 240))
        assertNull(BackgroundWork.activity(working.copy(backgroundSince = null), 240))
    }
}
