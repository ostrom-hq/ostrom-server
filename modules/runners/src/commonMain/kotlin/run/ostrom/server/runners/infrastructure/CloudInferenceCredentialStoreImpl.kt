// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.write
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.anyFrom
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.ReturningStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
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
 * @param database The JDBC pool.
 * @param tokens The launches module's agent tokens.
 * @param clock The clock.
 * @param logger Where a failed revocation is logged (the session's id and the error's class only).
 */
class CloudInferenceCredentialStoreImpl(
    private val database: JdbcDatabase,
    private val tokens: RunnerInferenceTokens,
    private val clock: Clock,
    private val logger: Logger,
) : CloudInferenceCredentialStore {
    override suspend fun issue(session: CloudSession, ttl: Duration, keep: Int): InferenceIssuance {
        val minted = tokens.issue(session, ttl) ?: return InferenceIssuance.NoLaunch
        var older: List<Uuid>? = null
        try {
            older = database.inOrg(session.orgId) { record(session, minted, keep) }
        } finally {
            // Not recorded (fenced, failed or cancelled): the token was never handed out, and is revoked at once.
            if (older == null) withContext(NonCancellable) { tokens.revoke(session.orgId, listOf(minted.id)) }
        }
        return older?.let { kept ->
            // Their rows are gone already: a failure is logged and the credential still returned (they expire).
            if (kept.isNotEmpty()) {
                guarded(session.orgId, "Revoking an execution's older inference credentials failed.") {
                    withContext(NonCancellable) { tokens.revoke(session.orgId, kept) }
                }
            }
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
    private fun JdbcTransaction.record(session: CloudSession, minted: InferenceToken, keep: Int): List<Uuid>? {
        val s = RunnersSessionsTable
        val row = s.select(s.epoch, s.state, s.desiredState, s.runnerTokenHash).where { s.id eq session.id }
            .forUpdate().firstOrNull()
        val current = row?.let {
            session.copy(
                epoch = it[s.epoch],
                state = SessionState.fromWire(it[s.state]),
                desiredState = DesiredState.fromWire(it[s.desiredState]),
                runnerTokenHash = it[s.runnerTokenHash],
            )
        }
        val live = current != null && current.runsInference && current.epoch == session.epoch &&
            current.runnerTokenHash == session.runnerTokenHash
        return if (live) insert(session, minted, keep) else null
    }

    /** Records [minted] and deletes the execution's credentials past the newest [keep], returning their tokens. */
    private fun JdbcTransaction.insert(session: CloudSession, minted: InferenceToken, keep: Int): List<Uuid> {
        // Creation times strictly increase per execution, so "newest" never depends on a tie-break: the session row
        // lock held here serializes the issuers of an execution.
        val c = RunnersInferenceCredentials
        val newest = c.createdAt.max()
        val latest = c.select(newest).where { (c.sessionId eq session.id) and (c.epoch eq session.epoch) }
            .single()[newest]
        val now = clock.now()
        val createdAt = if (latest == null) now else maxOf(now, latest + 1.milliseconds)
        write { credentialInsert(session, minted, createdAt) }
        return returned { credentialsPast(session, keep) }.map { it[c.tokenId] }
    }

    override suspend fun revokeEnded(session: CloudSession) {
        try {
            val live = session.runsInference
            val stale = database.inOrg(session.orgId) {
                val c = RunnersInferenceCredentials
                credentialsEnded(session, live).map { it[c.tokenId] }
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
        val stale = database.query {
            val c = RunnersStaleInferenceCredentials
            c.of(limit).select(c.orgId, c.tokenId).map { it[c.orgId] to it[c.tokenId] }
        }
        // One organization's failure never holds back the others: it is logged and tried again the next minute.
        return stale.groupBy({ it.first }, { it.second }).entries.sumOf { (orgId, ids) ->
            val done = guarded(orgId, "Purging an organization's stale inference credentials failed.") {
                revokeAndDelete(orgId, ids)
            }
            if (done) ids.size else 0
        }
    }

    /**
     * Runs [block], logging a failure (the organization and the error's class only) instead of throwing it; a
     * cancellation is rethrown.
     *
     * @return Whether [block] completed.
     */
    private suspend fun guarded(orgId: Uuid, message: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logger.warn(message, mapOf("orgId" to orgId.toString(), "error" to failure::class.simpleName))
        false
    }

    /** The tokens first, then their rows: a row outlives its token's revocation only if that revocation failed. */
    private suspend fun revokeAndDelete(orgId: Uuid, ids: List<Uuid>) {
        tokens.revoke(orgId, ids)
        database.inOrg(orgId) { write { credentialsForgotten(ids) } }
    }
}

/** A credential recorded, created at [createdAt]. */
internal fun StatementBuilder.credentialInsert(
    session: CloudSession,
    minted: InferenceToken,
    createdAt: Instant,
): InsertStatement<Number> = RunnersInferenceCredentials.insert {
    it[tokenId] = minted.id
    it[orgId] = session.orgId
    it[sessionId] = session.id
    it[epoch] = session.epoch
    it[this.createdAt] = createdAt
    it[expiresAt] = minted.expiresAt
}

/** The execution's credentials past its newest [keep], deleted; returns their tokens. */
internal fun StatementBuilder.credentialsPast(session: CloudSession, keep: Int): ReturningStatement {
    val c = RunnersInferenceCredentials
    val past = c.select(c.tokenId).where { (c.sessionId eq session.id) and (c.epoch eq session.epoch) }
        .orderBy(c.createdAt to SortOrder.DESC).offset(keep.toLong())
    return c.deleteReturning(listOf(c.tokenId)) { c.tokenId inSubQuery past }
}

/** The session's credentials of an execution that ended: all of them when it no longer runs, else the older ones. */
internal fun credentialsEnded(session: CloudSession, live: Boolean): Query {
    val c = RunnersInferenceCredentials
    val ended: Op<Boolean> = if (live) c.epoch neq session.epoch else Op.TRUE
    return c.select(c.tokenId).where { (c.sessionId eq session.id) and ended }
}

/** Credentials deleted once their tokens are revoked. */
internal fun StatementBuilder.credentialsForgotten(ids: List<Uuid>): DeleteStatement =
    RunnersInferenceCredentials.deleteWhere { RunnersInferenceCredentials.tokenId eq anyFrom(ids, UuidColumnType()) }
