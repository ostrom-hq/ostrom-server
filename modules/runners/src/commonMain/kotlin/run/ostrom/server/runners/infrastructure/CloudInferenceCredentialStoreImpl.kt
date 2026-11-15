// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.runners.domain.CloudInferenceCredentialStore
import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.InferenceIssuance
import run.ostrom.server.runners.domain.InferenceToken
import run.ostrom.server.runners.domain.IssuedInferenceCredential
import run.ostrom.server.runners.domain.RunnerInferenceTokens
import run.ostrom.server.runners.domain.SessionState
import run.ostrom.server.runners.domain.runsInference
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * [CloudInferenceCredentialStore] on `runners_inference_credentials` (RunnersMigrations version 21, TeamCode's
 * `SqlCloudInferenceCredentialStore`) and the launches module's agent tokens ([RunnerInferenceTokens]).
 *
 * The token lives in the identity module's table, written in its own transaction: it is issued before the session row
 * lock is taken, recorded under the lock only if the execution still runs, and revoked otherwise. An execution that
 * ends is revoked right after the write that ended it ([revokeEnded]): the token first, then its row, so a row is left
 * only for a token not revoked yet, which the minute's [purge] revokes. Every read and write of a credential runs in
 * its organization; only the purge's read crosses organizations, through `runners_stale_inference_credentials`, owned
 * by `ostrom_router`.
 *
 * @param driver The database.
 * @param tokens The launches module's agent tokens.
 * @param clock The clock.
 * @param logger Where a failed revocation is logged (the session's id and the error's class only).
 */
class CloudInferenceCredentialStoreImpl(
    private val driver: Driver,
    private val tokens: RunnerInferenceTokens,
    private val clock: Clock,
    private val logger: Logger,
) : CloudInferenceCredentialStore {
    override suspend fun issue(session: CloudSession, ttl: Duration, keep: Int): InferenceIssuance {
        val minted = tokens.issue(session, ttl) ?: return InferenceIssuance.NoLaunch
        var older: List<Uuid>? = null
        try {
            older = driver.inTenant(session.orgId) { record(session, minted, keep) }
        } finally {
            // Not recorded (fenced, failed or cancelled): the token was never handed out, and is revoked at once.
            if (older == null) withContext(NonCancellable) { tokens.revoke(session.orgId, listOf(minted.id)) }
        }
        return older?.let { kept ->
            if (kept.isNotEmpty()) tokens.revoke(session.orgId, kept)
            val lifetime = minted.expiresAt - clock.now()
            InferenceIssuance.Issued(IssuedInferenceCredential(minted.secret, minted.expiresAt, lifetime))
        } ?: InferenceIssuance.Fenced
    }

    /**
     * Under the session row lock, which every session write takes: records [minted] if the execution is still the
     * session's current, running one, and deletes the execution's credentials past the newest [keep].
     *
     * @return The tokens of the deleted credentials, to revoke; null when the execution is fenced.
     */
    private suspend fun QueryExecutor.record(session: CloudSession, minted: InferenceToken, keep: Int): List<Uuid>? {
        val row = fetchAll(
            Statement.create(
                "SELECT epoch, state, desired_state, runner_token_hash FROM runners_sessions " +
                    "WHERE id = :id FOR UPDATE",
            ).bind("id", session.id),
        ).getOrThrow().firstOrNull()
        val current = row?.let {
            session.copy(
                epoch = it.get("epoch").asLong(),
                state = SessionState.fromWire(it.get("state").asString()),
                desiredState = DesiredState.fromWire(it.get("desired_state").asString()),
                runnerTokenHash = it.get("runner_token_hash").asStringOrNull(),
            )
        }
        val live = current != null && current.runsInference && current.epoch == session.epoch &&
            current.runnerTokenHash == session.runnerTokenHash
        return if (live) insert(session, minted, keep) else null
    }

    /** Records [minted] and deletes the execution's credentials past the newest [keep], returning their tokens. */
    private suspend fun QueryExecutor.insert(session: CloudSession, minted: InferenceToken, keep: Int): List<Uuid> {
        // Creation times strictly increase per execution, so "newest" never depends on a tie-break.
        execute(
            Statement.create(
                """
                INSERT INTO runners_inference_credentials (token_id, org_id, session_id, epoch, created_at, expires_at)
                SELECT :token_id, :org_id, :session_id, :epoch, GREATEST(CAST(:now AS TIMESTAMPTZ),
                    COALESCE(max(created_at) + INTERVAL '1 millisecond', CAST(:now AS TIMESTAMPTZ))), :expires_at
                FROM runners_inference_credentials WHERE session_id = :session_id AND epoch = :epoch
                """,
            ).bind("token_id", minted.id).bind("org_id", session.orgId).bind("session_id", session.id)
                .bind("epoch", session.epoch).bind("now", clock.now()).bind("expires_at", minted.expiresAt),
        ).getOrThrow()
        return fetchAll(
            Statement.create(
                """
                DELETE FROM runners_inference_credentials WHERE token_id IN (
                    SELECT token_id FROM runners_inference_credentials WHERE session_id = :session_id AND epoch = :epoch
                    ORDER BY created_at DESC OFFSET :keep
                ) RETURNING token_id
                """,
            ).bind("session_id", session.id).bind("epoch", session.epoch).bind("keep", keep),
        ).getOrThrow().map { it.get("token_id").asUuid() }
    }

    override suspend fun revokeEnded(session: CloudSession) {
        try {
            val live = session.runsInference
            val stale = driver.inTenant(session.orgId) {
                fetchAll(
                    Statement.create(
                        """
                        SELECT token_id FROM runners_inference_credentials WHERE session_id = :session_id
                        AND (NOT CAST(:live AS BOOLEAN) OR epoch <> :epoch)
                        """,
                    ).bind("session_id", session.id).bind("live", live).bind("epoch", session.epoch),
                ).getOrThrow().map { it.get("token_id").asUuid() }
            }
            if (stale.isNotEmpty()) revokeAndDelete(session.orgId, stale)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // The session's write is committed: the purge revokes what is left within a minute.
            logger.warn(
                "Revoking an ended execution's inference credentials failed.",
                mapOf("sessionId" to session.id.toString(), "error" to failure::class.simpleName),
            )
        }
    }

    override suspend fun purge(limit: Int): Int {
        val stale = driver.fetchAll(
            Statement.create(
                "SELECT org_id, token_id FROM runners_stale_inference_credentials(CAST(:limit AS INTEGER))",
            ).bind("limit", limit),
        ).getOrThrow().map { it.get("org_id").asUuid() to it.get("token_id").asUuid() }
        stale.groupBy({ it.first }, { it.second }).forEach { (orgId, ids) -> revokeAndDelete(orgId, ids) }
        return stale.size
    }

    /** The tokens first, then their rows: a row outlives its token's revocation only if that revocation failed. */
    private suspend fun revokeAndDelete(orgId: Uuid, ids: List<Uuid>) {
        tokens.revoke(orgId, ids)
        driver.inTenant(orgId) {
            execute(
                Statement.create(
                    "DELETE FROM runners_inference_credentials WHERE " +
                        "token_id = ANY(CAST(string_to_array(CAST(:ids AS TEXT), ',') AS UUID[]))",
                ).bind("ids", ids.joinToString(",")),
            ).getOrThrow()
        }
    }
}
