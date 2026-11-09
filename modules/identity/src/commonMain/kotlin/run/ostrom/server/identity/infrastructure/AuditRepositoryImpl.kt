// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditEvent
import run.ostrom.server.identity.domain.AuditRepository
import run.ostrom.server.identity.domain.ChainedAuditEvent
import run.ostrom.server.identity.domain.OrgAuditQuery
import run.ostrom.server.kernel.database.inOrg
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private val DETAILS = MapSerializer(String.serializer(), String.serializer())
private val DETAILS_JSON = Json

/**
 * [AuditRepository] on PostgreSQL. An organization's chain is written and read through
 * [run.ostrom.server.kernel.database.inOrg], so row-level security confines it to that organization; the
 * account-level chain (`account`) has no organization.
 *
 * @param driver The database.
 */
class AuditRepositoryImpl(private val driver: Driver) : AuditRepository {
    override suspend fun append(
        orgId: Uuid?,
        build: suspend (previousHash: String?, sequence: Long) -> AuditEvent,
    ): AuditEvent {
        if (orgId == null) return driver.transaction { appendAudit(ACCOUNT_CHAIN, build) }
        return driver.inOrg(orgId) { appendAudit(orgId.toString(), build) }
    }

    override suspend fun list(orgId: Uuid?, actorId: Uuid?, beforeSequence: Long?, limit: Int): List<AuditEvent> {
        val before = beforeSequence ?: Long.MAX_VALUE
        if (orgId == null) {
            // The account-level chain is everybody's: it is only ever read for one actor (index on actor_id).
            val actor = requireNotNull(actorId) { "The account-level audit log is read for one user." }
            return driver.fetchAll(
                Statement.create(
                    "SELECT $COLUMNS FROM identity_audit_events WHERE org_id IS NULL AND actor_id = :actor " +
                        "AND sequence < :before ORDER BY sequence DESC LIMIT :limit",
                ).bind("actor", actor).bind("before", before).bind("limit", limit),
            ).getOrThrow().map { it.toEvent() }
        }
        return driver.inOrg(orgId) {
            fetchAll(
                Statement.create(
                    "SELECT $COLUMNS FROM identity_audit_events WHERE chain = :chain AND sequence < :before " +
                        "ORDER BY sequence DESC LIMIT :limit",
                ).bind("chain", orgId.toString()).bind("before", before).bind("limit", limit),
            ).getOrThrow().map { it.toEvent() }
        }
    }

    override suspend fun listOrg(orgId: Uuid, query: OrgAuditQuery): List<ChainedAuditEvent> {
        val statement = Statement.create(orgAuditSql(query.actorId != null, query.action != null))
            .bind("chain", orgId.toString()).bind("before", query.beforeSequence ?: Long.MAX_VALUE)
            .bind("limit", query.limit)
        query.actorId?.let { statement.bind("actor", it) }
        query.action?.let { statement.bind("action", it) }
        return driver.inOrg(orgId) {
            fetchAll(statement).getOrThrow().map { row ->
                ChainedAuditEvent(row.toEvent(), row.get("predecessor_hash").asStringOrNull())
            }
        }
    }

    private fun ResultSet.Row.toEvent() = AuditEvent(
        id = get("id").asUuid(),
        sequence = get("sequence").asLong(),
        entry = AuditEntry(
            orgId = get("org_id").asUuidOrNull(),
            actorId = get("actor_id").asUuidOrNull(),
            action = get("action").asString(),
            targetType = get("target_type").asString(),
            targetId = get("target_id").asString(),
            details = DETAILS_JSON.decodeFromString(DETAILS, get("details").asString()),
        ),
        occurredAt = get("occurred_at").asInstant(),
        previousHash = get("previous_hash").asStringOrNull(),
        hash = get("hash").asString(),
        signature = get("signature").asString(),
        keyId = get("key_id").asString(),
    )

    private companion object {
        const val ACCOUNT_CHAIN = "account"
        const val COLUMNS = "id, sequence, org_id, actor_id, action, target_type, target_id, details, occurred_at, " +
            "previous_hash, hash, signature, key_id"
    }
}

/**
 * Appends an event to [chain] inside this transaction: kept if and only if the transaction commits. Serializes the
 * appends of one chain until the transaction ends.
 *
 * @param chain An organization's id, or the account-level chain.
 * @param build Builds the event from the previous event's hash (null for the first) and the next sequence.
 */
internal suspend fun Transaction.appendAudit(
    chain: String,
    build: suspend (previousHash: String?, sequence: Long) -> AuditEvent,
): AuditEvent {
    // Serializes the appends of one chain until the transaction ends: no two events get the same sequence.
    val lock = Statement.create("SELECT pg_advisory_xact_lock(hashtext(:chain))").bind("chain", chain)
    execute(lock).getOrThrow()
    val last = fetchAll(
        Statement.create(
            "SELECT sequence, hash FROM identity_audit_events WHERE chain = :chain ORDER BY sequence " +
                "DESC LIMIT 1",
        ).bind("chain", chain),
    ).getOrThrow().firstOrNull()
    val event = if (last == null) {
        build(null, 1L)
    } else {
        build(last.get("hash").asString(), last.get("sequence").asLong() + 1)
    }
    execute(
        Statement.create(
            "INSERT INTO identity_audit_events (id, chain, sequence, org_id, actor_id, action, " +
                "target_type, target_id, " +
                "details, occurred_at, previous_hash, hash, signature, key_id) VALUES (:id, :chain, " +
                ":sequence, :org, " +
                ":actor, :action, :type, :target, :details, :at, :previous, :hash, :signature, :key)",
        ).bind("id", event.id).bind("chain", chain).bind("sequence", event.sequence)
            .bind("org", event.entry.orgId)
            .bind("actor", event.entry.actorId).bind("action", event.entry.action)
            .bind("type", event.entry.targetType).bind("target", event.entry.targetId)
            .bind("details", DETAILS_JSON.encodeToString(DETAILS, event.entry.details))
            .bind("at", event.occurredAt).bind("previous", event.previousHash).bind("hash", event.hash)
            .bind("signature", event.signature).bind("key", event.keyId),
    ).getOrThrow()
    return event
}

/**
 * The read of `GET /api/v1/audit`: an organization's lines, newest first, each with the hash of the line before it in
 * the chain (`sequence - 1`) whatever the filters, so its link can be checked. That hash is a scalar subquery, run only
 * for the lines of the page, each one lookup in the unique index `(chain, sequence)`: a join let the planner read and
 * materialize the whole chain for every page (1.5 s at p95 over 10 000 lines, IdentityReadsBenchTest). Only the
 * filters asked for are in the SQL, each value bound, never spliced.
 *
 * @param byActor Whether the read keeps one person's lines (`:actor`).
 * @param byAction Whether the read keeps one event name (`:action`).
 *
 * @return The SQL, with `:chain`, `:before` and `:limit`.
 */
internal fun orgAuditSql(byActor: Boolean, byAction: Boolean): String {
    val filters = listOfNotNull(
        "AND e.actor_id = :actor".takeIf { byActor },
        "AND e.action = :action".takeIf { byAction },
    ).joinToString(" ")
    return "SELECT e.id, e.sequence, e.org_id, e.actor_id, e.action, e.target_type, e.target_id, e.details, " +
        "e.occurred_at, e.previous_hash, e.hash, e.signature, e.key_id, " +
        "(SELECT p.hash FROM identity_audit_events p WHERE p.chain = e.chain AND p.sequence = e.sequence - 1) " +
        "AS predecessor_hash FROM identity_audit_events e " +
        "WHERE e.chain = :chain AND e.sequence < :before $filters ORDER BY e.sequence DESC LIMIT :limit"
}
