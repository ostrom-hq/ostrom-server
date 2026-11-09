// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.AgentKind
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.CloudRunner
import run.ostrom.server.runners.domain.ListRunnersUseCaseImpl
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.InMemoryCloudSessionsRepository
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.ORG_B
import run.ostrom.server.runners.fakes.SettableClock
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.presentation.RunnerDto
import run.ostrom.server.runners.presentation.RunnerRuntime
import run.ostrom.server.runners.presentation.toDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RunnersTest {
    private val t0 = Instant.parse("2026-10-30T10:00:00Z")
    private val clock = SettableClock(t0 + 120.seconds)
    private val repository = InMemoryCloudSessionsRepository()
    private val listRunners = ListRunnersUseCaseImpl(repository, clock)

    @Test
    fun onlySessionsHoldingAnExecutionHaveARunnerOnlineWhenItReportedWithinNinetySeconds() = runTest {
        val fresh = session(SessionState.RUNNING, createdAt = t0 + 3.seconds)
            .copy(epoch = 2, lastHeartbeatAt = clock.now - 90.seconds)
        val silent = session(SessionState.PAUSING, createdAt = t0 + 2.seconds)
            .copy(epoch = 1, lastHeartbeatAt = clock.now - 91.seconds)
        val starting = session(SessionState.CREATING, createdAt = t0 + 1.seconds)
        val paused = session(SessionState.PAUSED, createdAt = t0 + 4.seconds).copy(lastHeartbeatAt = clock.now)
        val ended = session(SessionState.TERMINATED, createdAt = t0 + 5.seconds)
        val bobs = session(SessionState.RUNNING, ownerId = "bob", createdAt = t0).copy(lastHeartbeatAt = clock.now)
        val elsewhere = session(SessionState.RUNNING, orgId = ORG_B, createdAt = t0).copy(lastHeartbeatAt = clock.now)
        listOf(fresh, silent, starting, paused, ended, bobs, elsewhere).forEach(repository::put)

        val runners = listRunners(Caller(ORG_A, "alice"))

        assertEquals(
            listOf(
                CloudRunner(fresh.id, fresh.name, fresh.agent, 2, clock.now - 90.seconds, online = true),
                CloudRunner(silent.id, silent.name, silent.agent, 1, clock.now - 91.seconds, online = false),
                CloudRunner(starting.id, starting.name, starting.agent, 0, null, online = false),
            ),
            runners,
        )
    }

    @Test
    fun aRunnerMapsToTheContractWithItsRuntimeAndAgent() {
        val id = Uuid.random()
        val online = CloudRunner(id, "review", AgentKind.CODEX, 1, t0, online = true)
        assertEquals(
            RunnerDto(id.toString(), "review", listOf("kubernetes", "codex"), "online", t0.toString(), "1.4.0"),
            online.toDto(RunnerRuntime("kubernetes", "ghcr.io/ostrom-hq/ostrom-runner:1.4.0")),
        )
        val waiting = online.copy(lastSeenAt = null, online = false)
        assertEquals(
            RunnerDto(id.toString(), "review", listOf("codex"), "offline", null, null),
            waiting.toDto(RunnerRuntime(null, "localhost:5000/ostrom-runner")),
        )
    }

    @Test
    fun theVersionIsTheImagesTagOnly() {
        assertEquals("latest", RunnerRuntime("docker", "ostrom-runner:latest").version)
        assertNull(RunnerRuntime("docker", "registry:5000/ostrom-runner").version)
        assertNull(RunnerRuntime("docker", "ostrom-runner:").version)
    }
}
