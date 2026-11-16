// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.kernel.workflows.SessionRunIds
import run.ostrom.server.runners.domain.RemoteHost
import run.ostrom.server.runners.domain.RemoteSessionRules
import run.ostrom.server.runners.domain.SessionKind
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.DESKTOP
import run.ostrom.server.runners.fakes.FakeSignedInDevices
import run.ostrom.server.runners.fakes.LAPTOP
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.presentation.CloudSessionHostDto
import run.ostrom.server.runners.presentation.RemoteHostViews
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** The host of a remote session as its answers show it (TeamCode's `CloudSessionHostView`). */
class RemoteHostViewsTest {
    private val clock = SettableClock()
    private val devices = FakeSignedInDevices()
    private val runs = FakeSessionRunIds()
    private val views = RemoteHostViews(devices, runs, clock)

    private fun remote(state: SessionState, host: RemoteHost = RemoteHost(LAPTOP, "ostrom", RemoteSessionRules.ASK)) =
        session(state = state).copy(kind = SessionKind.REMOTE, host = host)

    @Test
    fun aRemoteSessionShowsItsDeviceAndWhetherItsHostRunsItNow() = runTest {
        val beating = remote(SessionState.RUNNING).copy(lastHeartbeatAt = clock.now - 10.seconds)
        val draining = remote(SessionState.PAUSING).copy(lastHeartbeatAt = clock.now)
        val silent = remote(SessionState.RUNNING).copy(lastHeartbeatAt = clock.now - 91.seconds)
        val starting = remote(SessionState.CREATING)
        val removed = remote(SessionState.PAUSED, RemoteHost(null, "ostrom", RemoteSessionRules.BYPASS, "folder"))
        devices.names.remove(DESKTOP)
        val forgotten = remote(SessionState.RUNNING, RemoteHost(DESKTOP, "api", RemoteSessionRules.ASK))
        val cloud = session()

        val shown = views.responses(listOf(beating, draining, silent, starting, removed, forgotten, cloud))

        assertEquals(
            CloudSessionHostDto(
                deviceId = LAPTOP.toString(),
                deviceName = "Alice's laptop",
                platform = "macos",
                osVersion = "26.1",
                directoryName = "ostrom",
                online = true,
                lastHeartbeatAt = (clock.now - 10.seconds).toString(),
                permissionMode = "ask",
            ),
            shown[0].host,
        )
        assertEquals(listOf(true, true, false, false, false, false), shown.take(6).map { it.host?.online })
        assertNull(shown[3].host?.lastHeartbeatAt)
        assertEquals(listOf(null, null, "bypass", "folder"), shown[4].host.let {
            listOf(it?.deviceId, it?.deviceName, it?.permissionMode, it?.folderId)
        })
        assertEquals(DESKTOP.toString() to null, shown[5].host?.deviceId to shown[5].host?.deviceName)
        assertNull(shown[6].host)
        assertEquals("remote", views.response(beating).kind)
    }

    @Test
    fun withoutTheIdentityModuleNoDeviceIsNamed() = runTest {
        val shown = RemoteHostViews(null, null, clock).response(remote(SessionState.RUNNING))
        assertEquals(LAPTOP.toString() to null, shown.host?.deviceId to shown.host?.deviceName)
        assertEquals(emptyList(), views.responses(emptyList()))
        assertEquals(emptyList(), runs.asked, "No session, no query")
    }

    @Test
    fun aSessionAWorkflowRunStartedNamesItsRunAndAPageAsksOnce() = runTest {
        val started = session()
        val own = session()
        val hosted = remote(SessionState.RUNNING)
        runs.runs[started.id] = "wfr_1"

        val shown = views.responses(listOf(started, own, hosted))

        assertEquals(listOf("wfr_1", null, null), shown.map { it.runId })
        assertEquals(listOf(listOf(started.id, own.id, hosted.id)), runs.asked)
        assertNull(RemoteHostViews(devices, null, clock).response(started).runId, "Without the workflow-runs module")
    }

    /** The runs of the cloud sessions, and each batch asked. */
    private class FakeSessionRunIds : SessionRunIds {
        val runs = mutableMapOf<Uuid, String>()
        val asked = mutableListOf<List<Uuid>>()

        override suspend fun runsOf(orgId: Uuid, sessionIds: Collection<Uuid>): Map<Uuid, String> {
            asked += sessionIds.toList()
            return runs.filterKeys { it in sessionIds }
        }

        override suspend fun runsOfLaunches(orgId: Uuid, launchIds: Collection<String>): Map<String, String> =
            error("Not asked by the cloud sessions")
    }
}
