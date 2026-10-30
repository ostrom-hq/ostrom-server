// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.infrastructure

import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.domain.AuditEvent
import dev.ostrom.server.identity.domain.AuditRepository
import dev.ostrom.server.kernel.database.inOrg
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

/**
 * [AuditRepository] on PostgreSQL. An organization's chain is written and read through
 * [dev.ostrom.server.kernel.database.inOrg], so row-level security confines it to that organization; the
 * account-level chain (`account`) has no organization.
 *
 * @param driver The database.
 */
class AuditRepositoryImpl(private val driver: Driver) : AuditRepository {
    override suspend fun append(
        orgId: Uuid?,
        build: suspend (previousHash: String?, sequence: Long) -> AuditEvent,
    ): AuditEvent {
        val chain = orgId?.toString() ?: ACCOUNT_CHAIN
        val write: suspend Transaction.() -> AuditEvent = {
            // Serializes the appends of one chain until the transaction ends: no two events get the same sequence.
            val lock = Statement.create("SELECT pg_advisory_xact_lock(hashtext(:chain))").bind("chain", chain)
            execute(lock).getOrThrow()
            val last = fetchAll(
                Statement.create(
                    "SELECT sequence, hash FROM identity_audit_events WHERE chain = :chain ORDER BY sequence " +
                        "DESC LIMIT 1",
                ).bind("chain", chain),
            ).getOrThrow().firstOrNull()
            val event = build(last?.get("hash")?.asString(), (last?.get("sequence")?.asLong() ?: 0L) + 1)
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
            event
        }
        return if (orgId != null) driver.inOrg(orgId, write) else driver.transaction(write)
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
        val DETAILS = MapSerializer(String.serializer(), String.serializer())
        val DETAILS_JSON = Json
    }
}
