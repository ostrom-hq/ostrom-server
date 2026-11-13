// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.secrets.domain.Caller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The cancellation markers of inference calls, shared by every replica (TeamCode's `request_cancellations`): bounded,
 * expiring, scoped to the member who sent the call. Never a body or a prompt.
 */
interface RequestCancellationRepository {
    /**
     * Leaves a marker for [requestId] of [memberId], for [RETENTION]; nothing when it is already there (sending it
     * again neither extends it nor tells whether a call carries that id). Drops the org's expired markers first.
     *
     * @throws CancellationLimitException When the member holds [MEMBER_LIMIT] markers or the org [ORG_LIMIT].
     */
    suspend fun cancel(orgId: Uuid, memberId: Uuid, requestId: Uuid)

    /** Whether [memberId] left a marker, still alive, for [requestId]. */
    suspend fun isCancelled(orgId: Uuid, memberId: Uuid, requestId: Uuid): Boolean

    /** The bounds (TeamCode's). */
    companion object {
        /** How long a marker lasts. */
        val RETENTION: Duration = 10.minutes

        /** Markers per member at most. */
        const val MEMBER_LIMIT: Int = 256

        /** Markers per org at most. */
        const val ORG_LIMIT: Int = 4096
    }
}

/** The cancellation markers are full for the member or the org: 503 `cancellation_limit`. */
class CancellationLimitException : OstromException(
    ErrorKind.UNAVAILABLE,
    "Too many inference calls were cancelled lately. Retry shortly.",
    type = "cancellation_limit",
)

/** Stops one of the caller's inference calls by the id it was sent with (`cancelInferenceRequest`). */
interface CancelInferenceRequestUseCase {
    /**
     * Leaves the marker every replica reads; the same for an id no call carries.
     *
     * @param caller The member: only their own calls stop.
     * @param requestId The call's `x-ostrom-client-request-id`.
     */
    suspend operator fun invoke(caller: Caller, requestId: Uuid)
}

/** [CancelInferenceRequestUseCase] on the markers. */
class CancelInferenceRequestUseCaseImpl(private val repository: RequestCancellationRepository) :
    CancelInferenceRequestUseCase {
    override suspend fun invoke(caller: Caller, requestId: Uuid) =
        repository.cancel(caller.orgId, caller.memberId, requestId)
}

/**
 * Runs a call the client can stop (TeamCode's `RequestCancellationService.run`): a call whose marker is already there
 * never starts, and a running one, queued, between accounts or streaming, stops within [POLL] of its marker, on
 * whichever replica it runs. The markers are read only for a call that carries an id.
 *
 * @param repository The markers.
 * @param pause Waits between two reads ([delay]).
 */
class CancellationWatch(
    private val repository: RequestCancellationRepository,
    private val pause: suspend (Duration) -> Unit = { delay(it) },
) {
    /**
     * Runs [block] for [caller]'s call [requestId].
     *
     * @throws CancellationException When the call was cancelled, before or while it ran.
     */
    suspend fun <T> run(caller: Caller, requestId: Uuid, block: suspend () -> T): T {
        if (cancelled(caller, requestId)) throw CancellationException(CANCELLED)
        return coroutineScope {
            val work = async { block() }
            val monitor = launch {
                while (!work.isCompleted) {
                    pause(POLL)
                    if (cancelled(caller, requestId)) work.cancel(CancellationException(CANCELLED))
                }
            }
            try {
                work.await()
            } finally {
                monitor.cancelAndJoin()
            }
        }
    }

    /** Whether the marker is there; a read that fails leaves the call running, as TeamCode's next poll would. */
    private suspend fun cancelled(caller: Caller, requestId: Uuid): Boolean = try {
        repository.isCancelled(caller.orgId, caller.memberId, requestId)
    } catch (stopped: CancellationException) {
        throw stopped
    } catch (_: Exception) {
        false
    }

    /** The pace of the reads. */
    companion object {
        /** How often a running call reads its marker. */
        val POLL: Duration = 500.milliseconds

        private const val CANCELLED = "The inference call was cancelled."
    }
}

/** An inference call's id that is not one lowercase UUID: 400 `invalid_request_id`. */
class InvalidRequestIdException : OstromException(
    ErrorKind.INVALID_REQUEST,
    "A request id is a lowercase UUID.",
    type = "invalid_request_id",
)

/** The client's own ids of its inference calls, in `x-ostrom-client-request-id`. */
object ClientRequestIds {
    private val LOWERCASE_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** The id [value] names, or null when it is not one lowercase UUID (a header sent twice is joined, so refused). */
    fun parse(value: String): Uuid? = if (LOWERCASE_UUID.matches(value)) Uuid.parse(value) else null
}
