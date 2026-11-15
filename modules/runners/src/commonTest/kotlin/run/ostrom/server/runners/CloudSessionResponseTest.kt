// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.SessionFailure
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.runners.presentation.toResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CloudSessionResponseTest {
    @Test
    fun aSessionMapsEveryTimeAndItsFailure() {
        val t0 = Instant.parse("2026-10-30T10:00:00Z")
        val response = session(state = SessionState.TERMINATED, desired = DesiredState.TERMINATED, createdAt = t0)
            .copy(
                failure = SessionFailure("idle_timeout", "Paused."),
                readyAt = t0 + 1.minutes,
                lastActivityAt = t0 + 2.minutes,
                pausedAt = t0 + 3.minutes,
                terminatedAt = t0 + 4.minutes,
            ).toResponse()
        assertEquals("idle_timeout", response.failure?.code)
        assertEquals("Paused.", response.failure?.message)
        assertEquals("2026-10-30T10:01:00Z", response.readyAt)
        assertEquals("2026-10-30T10:02:00Z", response.lastActivityAt)
        assertEquals("2026-10-30T10:03:00Z", response.pausedAt)
        assertEquals("2026-10-30T10:04:00Z", response.endedAt)
        assertEquals("terminated", response.status)
        assertNull(response.terminatedReason, "A session terminated before the reason was recorded")
    }

    @Test
    fun anExpiryIsServedOnAPausedSessionAndTheReasonOnATerminatedOne() {
        val t0 = Instant.parse("2026-10-30T10:00:00Z")
        val paused = session(state = SessionState.PAUSED, desired = DesiredState.TERMINATED, createdAt = t0)
            .copy(expiresAt = t0 + 3.minutes, terminatedReason = "expired")
        assertEquals("2026-10-30T10:03:00Z", paused.toResponse().expiresAt)
        assertNull(paused.toResponse().terminatedReason, "Not terminated yet")
        val terminated = paused.copy(state = SessionState.TERMINATED)
        assertNull(terminated.toResponse().expiresAt)
        assertEquals("expired", terminated.toResponse().terminatedReason)
    }

    @Test
    fun aSessionBeingTransferredNamesItsTransfer() {
        val id = Uuid.random()
        val moving = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED)
        assertEquals(id.toString(), moving.copy(transferId = id).toResponse().transferId)
        assertNull(moving.toResponse().transferId)
    }
}
