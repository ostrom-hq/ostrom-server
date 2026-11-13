// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.CancelInferenceRequestUseCaseImpl
import run.ostrom.server.gateway.domain.CancellationLimitException
import run.ostrom.server.gateway.domain.CancellationWatch
import run.ostrom.server.gateway.domain.ClientRequestIds
import run.ostrom.server.gateway.domain.InvalidRequestIdException
import run.ostrom.server.gateway.domain.RequestCancellationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** Markers kept in memory, by org, member and call; [failure] makes every read fail. */
class InMemoryCancellations : RequestCancellationRepository {
    val markers = mutableSetOf<Triple<Uuid, Uuid, Uuid>>()
    var failure: Exception? = null
    var reads = 0

    override suspend fun cancel(orgId: Uuid, memberId: Uuid, requestId: Uuid) {
        markers += Triple(orgId, memberId, requestId)
    }

    override suspend fun isCancelled(orgId: Uuid, memberId: Uuid, requestId: Uuid): Boolean {
        reads++
        failure?.let { throw it }
        return Triple(orgId, memberId, requestId) in markers
    }
}

/** Cancelling an inference call by the client's id: before it starts, while it runs, never another member's. */
class RequestCancellationTest {
    private val markers = InMemoryCancellations()
    private val watch = CancellationWatch(markers)
    private val id = Uuid.random()

    @Test
    fun anIdIsOneLowercaseUuid() {
        assertEquals(id, ClientRequestIds.parse(id.toString()))
        assertNull(ClientRequestIds.parse(id.toString().uppercase()))
        assertNull(ClientRequestIds.parse("$id,$id"), "Sent twice")
        assertNull(ClientRequestIds.parse(""))
        assertEquals("invalid_request_id", InvalidRequestIdException().type)
        assertEquals("cancellation_limit", CancellationLimitException().type)
    }

    @Test
    fun aCallRunsUntilItsOwnMemberCancelsIt() = runTest {
        assertEquals("done", watch.run(Gw.memberA, id) { "done" })
        CancelInferenceRequestUseCaseImpl(markers)(Gw.otherA, id)
        assertEquals("done", watch.run(Gw.memberA, id) { "done" }, "Another member's marker stops nothing")

        CancelInferenceRequestUseCaseImpl(markers)(Gw.memberA, id)
        var started = false
        assertFailsWith<CancellationException> { watch.run(Gw.memberA, id) { started = true } }
        assertEquals(false, started, "A call cancelled before it starts never starts")
    }

    @Test
    fun aRunningCallStopsWithinAPollOfItsMarker() = runTest {
        val running = Uuid.random()
        var finished = false
        var paused = 0.milliseconds
        // The pauses that ended: the one begun as the call stopped is cancelled with it.
        val timed = CancellationWatch(markers) {
            delay(it)
            paused += it
        }
        assertFailsWith<CancellationException> {
            timed.run(Gw.memberA, running) {
                delay(2_100.milliseconds)
                markers.cancel(Gw.orgA, Gw.memberA.memberId, running)
                delay(10.minutes)
                finished = true
            }
        }
        assertEquals(false, finished)
        assertEquals(2_500.milliseconds, paused, "Stopped at the next poll")
    }

    @Test
    fun aMarkerThatCannotBeReadLeavesTheCallRunning() = runTest {
        markers.failure = IllegalStateException("database down")
        assertEquals(1, watch.run(Gw.memberA, id) { delay(2_100.milliseconds).let { 1 } })
        assertEquals(5, markers.reads, "Read before, then every half second")

        markers.failure = CancellationException("stopped")
        assertFailsWith<CancellationException> { watch.run(Gw.memberA, id) { 1 } }
    }
}
