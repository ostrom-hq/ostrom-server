// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * An execution's inference credential, returned once to the runner of the execution it belongs to (TeamCode's
 * `IssuedInferenceCredential`); never logged.
 *
 * @property token The credential, the bearer of the agent's model calls through the runner's loopback proxy.
 * @property expiresAt When the server stops accepting it.
 * @property lifetime Its lifetime from now, which the runner measures on its own clock.
 */
class IssuedInferenceCredential(val token: String, val expiresAt: Instant, val lifetime: Duration) {
    override fun toString(): String = "IssuedInferenceCredential(token=[REDACTED], expiresAt=$expiresAt)"
}

/**
 * An agent token of the launch an execution's runner opened (`POST /api/v1/cloud/runner/launch`), issued for the
 * execution's inference; never logged.
 *
 * @property id The token's id, which revokes it.
 * @property secret The token, handed to the runner once.
 * @property expiresAt When it expires.
 */
class InferenceToken(val id: Uuid, val secret: String, val expiresAt: Instant) {
    override fun toString(): String = "InferenceToken(id=$id, secret=[REDACTED], expiresAt=$expiresAt)"
}

/**
 * The launches module's agent tokens for an execution's inference (bound by the launches module, which opens the
 * launch of a cloud session; absent without it, and then the route is not served). The token is an additional agent
 * token of the execution's own launch, so the inference gateway reads it as that launch's agent: the owner's model
 * policy, budgets and usage, and refused once the execution no longer runs.
 */
interface RunnerInferenceTokens {
    /**
     * Issues a token of the launch the session's current execution opened.
     *
     * @param session The session, as its current execution's runner token found it.
     * @param lifetime How long the token lives at most: never past the launch's own expiry.
     *
     * @return The token, or null when the execution opened no launch that is still open (none yet, ended, expired).
     */
    suspend fun issue(session: CloudSession, lifetime: Duration): InferenceToken?

    /**
     * Revokes tokens it issued, at once: the gateway refuses them from then on.
     *
     * @param orgId Their organization.
     * @param ids The tokens.
     */
    suspend fun revoke(orgId: Uuid, ids: List<Uuid>)
}

/** What [CloudInferenceCredentialStore.issue] did. */
sealed interface InferenceIssuance {
    /**
     * The credential was issued and recorded with its execution.
     *
     * @property credential The credential.
     */
    class Issued(val credential: IssuedInferenceCredential) : InferenceIssuance

    /** The execution is no longer the session's current, running one. */
    data object Fenced : InferenceIssuance

    /** The execution opened no launch that is still open. */
    data object NoLaunch : InferenceIssuance
}

/**
 * Inference credentials of cloud executions (TeamCode's `CloudInferenceCredentialStore`): each is an agent token of
 * the execution's launch, linked to its execution in `runners_inference_credentials`.
 *
 * Revocation is not a separate call: every write of a session ([revokeEnded], told by the sessions repository once
 * the write committed) revokes the credentials of an execution that ended, of an earlier epoch, or of a session that
 * left the running states; [purge] catches what such a revocation missed.
 */
interface CloudInferenceCredentialStore {
    /**
     * Issues a credential for the session's current execution, valid for [ttl], and keeps at most [keep] per
     * execution (the older ones are deleted and revoked). The check and the record happen under the session row lock,
     * so an execution that ends concurrently never keeps a credential.
     *
     * @param session The session, as its current execution's runner token found it.
     * @param ttl How long the credential lives.
     * @param keep How many credentials of the execution live at most, this one included.
     *
     * @return What happened.
     */
    suspend fun issue(session: CloudSession, ttl: Duration, keep: Int): InferenceIssuance

    /**
     * Revokes and deletes the credentials of [session] that no longer belong to a live execution: all of them when
     * it no longer runs (or is being deleted), those of its earlier epochs otherwise. Told of every session write once
     * it committed; a failure is logged and left to [purge].
     *
     * @param session The session as it was just written.
     */
    suspend fun revokeEnded(session: CloudSession)

    /**
     * Revokes and deletes a bounded batch of the expired credentials of every organization, and of those left behind
     * by an execution that no longer runs (TeamCode's `purge`).
     *
     * @param limit How many at most.
     *
     * @return How many were deleted.
     */
    suspend fun purge(limit: Int): Int
}

/**
 * Told of a session once a write of it committed: [CloudInferenceCredentialStore.revokeEnded], bound by the runners
 * module.
 */
fun interface CloudSessionWritten {
    /**
     * @param session The session as it was just written.
     */
    suspend fun written(session: CloudSession)
}

/** Nobody is told: no inference credential is served. */
object NoCloudSessionWritten : CloudSessionWritten {
    override suspend fun written(session: CloudSession) = Unit
}

/**
 * Whether a session's current execution may hold an inference credential: it holds an execution (`creating`,
 * `resuming`, `running`, `pausing`: a draining execution finishes its turn) and is not being deleted.
 */
val CloudSession.runsInference: Boolean
    get() = state.holdsExecution && desiredState != DesiredState.TERMINATED

/**
 * The runner's inference credential (`POST /api/v1/cloud/runner/inference`, `runners.cloud.inference.v1`, TeamCode's
 * `RunnerInferenceUseCase`). The runner keeps it in memory and forwards the agent's inference through its loopback
 * proxy; the agent itself only ever sees a random local token. Credentials are short-lived and renewed by the runner
 * well before they expire, so a credential copied out of the runner's memory is useless after [TTL] even if revocation
 * were missed.
 */
interface RunnerInferenceUseCase {
    /**
     * Issues the credential.
     *
     * @param token The runner's credential, as presented.
     * @param request The session and execution the runner names.
     *
     * @return The credential.
     *
     * @throws RunnerUnauthenticatedException When no current execution holds the token.
     * @throws ExecutionFencedException When the token is not the one of the session and execution named, or the
     *   execution no longer runs.
     * @throws InferenceUnavailableException When the session runs a shell, or its execution opened no launch.
     */
    suspend operator fun invoke(token: String, request: RunnerExecutionRequest): IssuedInferenceCredential

    /** TeamCode's bounds. */
    companion object {
        /** How long a credential lives. */
        val TTL: Duration = 1.hours

        /** The current credential and the one before it, so a request in flight during a renewal is not cut. */
        const val KEEP: Int = 2
    }
}

/**
 * [RunnerInferenceUseCase] on the runner credentials and the inference credentials.
 *
 * @param runners Finds the session of the presented token (the runner token, or the pod's).
 * @param credentials The inference credentials.
 */
class RunnerInferenceUseCaseImpl(
    private val runners: RunnerCredentials,
    private val credentials: CloudInferenceCredentialStore,
) : RunnerInferenceUseCase {
    override suspend fun invoke(token: String, request: RunnerExecutionRequest): IssuedInferenceCredential {
        val session = runners.session(token) ?: throw RunnerUnauthenticatedException()
        if (session.id != request.sessionId || session.epoch != request.epoch) throw ExecutionFencedException()
        // A session being deleted, or no longer holding the execution, fences its runner (TeamCode's terminal check).
        if (!session.runsInference) throw ExecutionFencedException()
        // A shell runs no agent: no launch, no model call.
        if (session.agent == AgentKind.SHELL) throw InferenceUnavailableException()
        return when (val issued = credentials.issue(session, RunnerInferenceUseCase.TTL, RunnerInferenceUseCase.KEEP)) {
            is InferenceIssuance.Issued -> issued.credential
            InferenceIssuance.Fenced -> throw ExecutionFencedException()
            InferenceIssuance.NoLaunch -> throw InferenceUnavailableException()
        }
    }
}
