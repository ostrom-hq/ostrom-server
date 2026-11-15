// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches

import run.ostrom.server.launches.domain.models.Launch
import run.ostrom.server.launches.fakes.ADA
import run.ostrom.server.launches.fakes.FixedClock
import run.ostrom.server.launches.fakes.InMemoryLaunches
import run.ostrom.server.launches.fakes.ORG
import run.ostrom.server.launches.fakes.OTHER_ORG
import run.ostrom.server.launches.infrastructure.LaunchesLocalLaunches
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** The launches a local session may be opened for (TeamCode's `localRun`): the person's own, still running. */
class LaunchesLocalLaunchesTest {
    private val clock = FixedClock()
    private val launches = InMemoryLaunches()
    private val ended = mutableSetOf<String>()
    private val local = LaunchesLocalLaunches(launches, { _, id -> id !in ended }, clock)

    private fun launch(id: String) = Launch(
        id = id,
        orgId = ORG,
        userId = ADA.userId,
        dev = ADA.login,
        repo = null,
        agent = "claude-code",
        model = null,
        createdAt = clock.now,
        expiresAt = clock.now + 12.hours,
    ).also(launches::create)

    @Test
    fun onlyThePersonsOwnRunningLaunchOpensALocalSession() = runTest {
        launch("lch_1")
        assertTrue(local.running(ORG, ADA.userId.toString(), "lch_1"))
        assertFalse(local.running(ORG, Uuid.random().toString(), "lch_1"), "Another person's")
        assertFalse(local.running(OTHER_ORG, ADA.userId.toString(), "lch_1"), "Another organization's")
        assertFalse(local.running(ORG, ADA.userId.toString(), "lch_2"), "Unknown")
        ended += "lch_1"
        assertFalse(local.running(ORG, ADA.userId.toString(), "lch_1"), "Ended: its agent tokens are revoked")
    }

    @Test
    fun anExpiredLaunchRunsNoMore() = runTest {
        launch("lch_3")
        clock.now += 13.hours
        assertFalse(local.running(ORG, ADA.userId.toString(), "lch_3"))
    }
}
