// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.gateway.domain.AgentDirective
import run.ostrom.server.gateway.domain.ClaimExpiredException
import run.ostrom.server.gateway.domain.DeliveryStage
import run.ostrom.server.gateway.domain.DirectiveDelivery
import run.ostrom.server.gateway.domain.DirectiveExpiredException
import run.ostrom.server.gateway.domain.DirectiveRepository
import run.ostrom.server.gateway.domain.DirectiveWrite
import run.ostrom.server.gateway.domain.Pace
import run.ostrom.server.gateway.domain.PacingAdvice
import run.ostrom.server.gateway.domain.PacingRecovery
import run.ostrom.server.gateway.domain.StoredDirective
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.databaseNowPlus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.notExists
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * [DirectiveRepository] on PostgreSQL ([GatewayDirectives], [GatewayDirectiveDeliveries], [GatewayDirectiveReceipts];
 * TeamCode's `SqlDirectiveService` writes and `SqlDirectiveDelivery`), every query confined to its org
 * ([JdbcDatabase.inOrg]) and filtered on it. The controller writes under the org's admission lock, so the spacing and
 * the advice change together; a delivery claim runs under the org's delivery lock, so two hooks never both deliver.
 *
 * @param database The JDBC pool.
 */
class DirectiveRepositoryImpl(private val database: JdbcDatabase) : DirectiveRepository {
    override suspend fun stored(orgId: Uuid): Map<Pair<Uuid, String>, StoredDirective> = database.inOrg(orgId) {
        GatewayDirectives.selectAll().where { GatewayDirectives.orgId eq orgId }.associate { row ->
            (row[GatewayDirectives.memberId] to row[GatewayDirectives.scopeKey]) to StoredDirective(
                row.directive(),
                row[GatewayDirectives.contextKey],
                row[GatewayDirectives.recoveryPace]?.let {
                    PacingRecovery(
                        Pace.valueOf(it),
                        row[GatewayDirectives.recoveryStartedAt] ?: row[GatewayDirectives.createdAt],
                        (row[GatewayDirectives.recoveryMinIntervalMs] ?: 0).milliseconds,
                    )
                },
            )
        }
    }

    override suspend fun save(orgId: Uuid, writes: List<DirectiveWrite>) = database.inOrg(orgId) {
        lockUntilCommit(admissionLockKey(orgId))
        writes.forEach { write(orgId, it) }
        purge(orgId)
    }

    private fun JdbcTransaction.write(orgId: Uuid, write: DirectiveWrite) {
        val d = GatewayDirectives
        val advice = write.directive.advice
        // The last admission is the spacing's: never written here, so an upsert keeps it.
        d.upsert(d.orgId, d.memberId, d.scopeKey) {
            it[d.orgId] = orgId
            it[memberId] = write.memberId
            it[scopeKey] = write.scopeKey
            it[id] = write.directive.id
            it[pace] = advice.pace.name
            it[maxConcurrent] = advice.maxConcurrent
            it[minIntervalMs] = advice.minInterval.inWholeMilliseconds
            it[message] = advice.message
            it[createdAt] = write.directive.createdAt
            it[expiresAt] = write.directive.expiresAt
            it[contextKey] = write.contextKey
            it[recoveryPace] = write.recovery?.pace?.name
            it[recoveryStartedAt] = write.recovery?.startedAt
            it[recoveryMinIntervalMs] = write.recovery?.minInterval?.inWholeMilliseconds
        }
    }

    private fun JdbcTransaction.purge(orgId: Uuid) {
        val d = GatewayDirectives
        val r = GatewayDirectiveDeliveries
        d.deleteWhere { (d.orgId eq orgId) and (expiresAt lessEq databaseNow()) }
        r.deleteWhere {
            (r.orgId eq orgId) and (
                (updatedAt less databaseNowPlus(-RETENTION_SECONDS)) or notExists(
                    d.select(d.memberId).where {
                        (d.orgId eq orgId) and (d.memberId eq r.memberId) and (d.scopeKey eq r.sessionKey) and
                            (d.expiresAt greater databaseNow())
                    },
                )
                )
        }
        GatewayDirectiveReceipts.deleteWhere {
            (GatewayDirectiveReceipts.orgId eq orgId) and (receivedAt less databaseNowPlus(-RETENTION_SECONDS))
        }
    }

    override suspend fun current(orgId: Uuid, memberId: Uuid, session: String?): AgentDirective? =
        database.inOrg(orgId) { read(orgId, memberId, scopeOf(session)) }

    override suspend fun claim(
        orgId: Uuid,
        memberId: Uuid,
        session: String,
        stage: DeliveryStage,
        tokenId: String,
    ): DirectiveDelivery? = database.inOrg(orgId) {
        lockUntilCommit(deliveryLockKey(orgId))
        val scope = sessionDigest(session)
        val directive = read(orgId, memberId, scope) ?: return@inOrg null
        val r = GatewayDirectiveDeliveries
        val key = deliveryKey(orgId, memberId, scope, stage)
        val previous = r.select(r.directiveId).where { key }.singleOrNull()
        val delivered = previous != null && !r.select(r.directiveId).where {
            val pending = r.emittedAt.isNotNull() or (r.claimedUntil greater databaseNow())
            key and (r.directiveId eq directive.id) and pending
        }.empty()
        val full = previous == null && (
            r.selectAll().where { r.orgId eq orgId }.count() >= DirectiveRepository.MAX_DELIVERIES ||
                r.selectAll().where { (r.orgId eq orgId) and (r.memberId eq memberId) }.count() >=
                DirectiveRepository.MAX_MEMBER_DELIVERIES
            )
        if (delivered || full) return@inOrg null
        val claimId = Uuid.random().toString()
        r.upsert(r.orgId, r.memberId, r.sessionKey, r.stage) {
            it[r.orgId] = orgId
            it[r.memberId] = memberId
            it[sessionKey] = scope
            it[r.stage] = stage.name
            it[directiveId] = directive.id
            it[r.claimId] = claimId
            it[r.tokenId] = tokenId
            it[claimedUntil] = databaseNowPlus(DirectiveRepository.CLAIM_LEASE.inWholeSeconds)
            it[emittedAt] = null
            it[updatedAt] = databaseNow()
        }
        DirectiveDelivery(directive, claimId)
    }

    @Suppress("LongParameterList")
    override suspend fun acknowledge(
        orgId: Uuid,
        memberId: Uuid,
        id: String,
        stage: DeliveryStage,
        session: String?,
        claimId: String?,
        tokenId: String,
    ) = database.inOrg(orgId) {
        lockUntilCommit(deliveryLockKey(orgId))
        val scope = scopeOf(session)
        val d = GatewayDirectives
        val live = d.select(d.id).where {
            (d.orgId eq orgId) and (d.memberId eq memberId) and (d.scopeKey eq scope) and (d.id eq id) and
                (d.expiresAt greater databaseNow())
        }
        if (live.empty()) throw DirectiveExpiredException()
        if (session != null) settle(orgId, memberId, scope, stage, id, claimId.orEmpty(), tokenId)
        GatewayDirectiveReceipts.upsert(
            GatewayDirectiveReceipts.orgId,
            GatewayDirectiveReceipts.directiveId,
            GatewayDirectiveReceipts.tokenId,
            GatewayDirectiveReceipts.stage,
        ) {
            it[this.orgId] = orgId
            it[directiveId] = id
            it[this.tokenId] = tokenId
            it[this.stage] = stage.name
            it[receivedAt] = databaseNow()
        }
        Unit
    }

    @Suppress("LongParameterList")
    private fun JdbcTransaction.settle(
        orgId: Uuid,
        memberId: Uuid,
        scope: String,
        stage: DeliveryStage,
        id: String,
        claimId: String,
        tokenId: String,
    ) {
        val r = GatewayDirectiveDeliveries
        val key = deliveryKey(orgId, memberId, scope, stage)
        val held = r.select(r.claimId).where {
            key and (r.directiveId eq id) and (r.claimId eq claimId) and (r.tokenId eq tokenId) and
                (r.claimedUntil greater databaseNow())
        }
        if (held.empty()) throw ClaimExpiredException()
        r.update({ key }) {
            it[emittedAt] = databaseNow()
            it[updatedAt] = databaseNow()
        }
    }

    private fun JdbcTransaction.read(orgId: Uuid, memberId: Uuid, scope: String): AgentDirective? {
        val d = GatewayDirectives
        return d.selectAll().where {
            (d.orgId eq orgId) and (d.memberId eq memberId) and (d.scopeKey eq scope) and
                (d.expiresAt greater databaseNow())
        }.singleOrNull()?.directive()
    }
}

/** The scope key of a session: '' for the member-wide advice, else the SHA-256 digest of its id in hexadecimal. */
internal fun scopeOf(session: String?): String = session?.let(::sessionDigest).orEmpty()

/** The SHA-256 digest of a session id in hexadecimal, as `sharing_affinities.session_hash` stores it. */
internal fun sessionDigest(session: String): String =
    MessageDigest.getInstance("SHA-256").digest(session.encodeToByteArray()).toHexString()

/** One delivery's row. */
private fun deliveryKey(orgId: Uuid, memberId: Uuid, scope: String, stage: DeliveryStage) =
    GatewayDirectiveDeliveries.let { r ->
        (r.orgId eq orgId) and (r.memberId eq memberId) and (r.sessionKey eq scope) and (r.stage eq stage.name)
    }

/** The advisory lock key of an org's deliveries. */
internal fun deliveryLockKey(orgId: Uuid): String = "gateway_directive_deliveries:$orgId"

private val RETENTION_SECONDS = DirectiveRepository.RETENTION.inWholeSeconds

private fun ResultRow.directive(): AgentDirective = AgentDirective(
    id = this[GatewayDirectives.id],
    memberId = this[GatewayDirectives.memberId].toString(),
    advice = PacingAdvice(
        Pace.valueOf(this[GatewayDirectives.pace]),
        this[GatewayDirectives.maxConcurrent],
        this[GatewayDirectives.minIntervalMs].milliseconds,
        this[GatewayDirectives.message],
    ),
    createdAt = this[GatewayDirectives.createdAt],
    expiresAt = this[GatewayDirectives.expiresAt],
)
