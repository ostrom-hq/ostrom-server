// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.CloudSessionsRepository
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.fakes.session
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Against a real PostgreSQL: a session's expiry and why it ended (`runners.cloud.retention.v1`). */
class CloudSessionExpiryPostgresTest {
    private val t0 = Instant.parse("2026-10-30T10:00:00Z")

    @Test
    fun theExpiryAndTheReasonAreStoredAndRead() = serverTest(listOf(RunnersModule)) { server ->
        val repository = server.koin.get<CloudSessionsRepository>()
        val created = session(state = SessionState.PAUSED, desired = DesiredState.PAUSED, createdAt = t0)
            .copy(pausedAt = t0, expiresAt = t0 + 30.days)
        repository.createWithinCap(created, 5)
        assertEquals(created, repository.getById(created.id))

        val expired = created.copy(
            desiredState = DesiredState.TERMINATED,
            terminatedReason = CloudSession.TERMINATED_EXPIRED,
        )
        val stored = assertNotNull(repository.compareAndSet(expired, t0))
        assertEquals(CloudSession.TERMINATED_EXPIRED, stored.terminatedReason)
        assertEquals(t0 + 30.days, stored.expiresAt)
        assertEquals(stored, repository.getById(created.id))
    }
}
