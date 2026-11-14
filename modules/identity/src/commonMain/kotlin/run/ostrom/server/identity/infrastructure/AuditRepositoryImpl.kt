// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.AuditEntry
import run.ostrom.server.identity.domain.AuditEvent
import run.ostrom.server.identity.domain.AuditRepository
import run.ostrom.server.identity.domain.ChainedAuditEvent
import run.ostrom.server.identity.domain.OrgAuditQuery
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.lockUntilCommit
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

private val DETAILS = MapSerializer(String.serializer(), String.serializer())
private val DETAILS_JSON = Json

/** The chain of the account-level events, which belong to no organization. */
private const val ACCOUNT_CHAIN = "account"

/**
 * [AuditRepository] on PostgreSQL through Exposed ([IdentityAuditEvents]). An organization's chain is a tenant's: it is
 * written and read in [JdbcDatabase.inOrg], every query filtered on the organization too, so row-level security still
 * confines it to that organization; the account-level chain (`account`) has no organization and is reached outside
 * any ([JdbcDatabase.query]).
 *
 * Other modules append to an organization's chain inside their own sqlx4k transaction ([TransactionalAuditLog],
 * [Transaction.appendAudit]); both writers take the same advisory lock on the chain and read its head the same way,
 * so a chain written by both stays one chain.
 *
 * @param database The JDBC pool.
 */
class AuditRepositoryImpl(private val database: JdbcDatabase) : AuditRepository {
    private val t = IdentityAuditEvents

    /**
     * Builds the event on the chain's head, read first, then appends it in a second transaction if that head is still
     * the chain's under the lock ([appendOnHead]); if another writer appended meanwhile, the event is built again on
     * the new head. [build] is `suspend` (it signs through the instance's [run.ostrom.server.kernel.signing
     * .InstanceSigner], whose contract may reach a key held elsewhere) and an Exposed block is not, so it runs between
     * the transactions, never inside one: no thread blocks on it while holding a connection and the chain's lock.
     * Each round where the head moved is another writer's append, so the chains always progress.
     */
    override suspend fun append(
        orgId: Uuid?,
        build: suspend (previousHash: String?, sequence: Long) -> AuditEvent,
    ): AuditEvent {
        val chain = if (orgId == null) ACCOUNT_CHAIN else orgId.toString()
        var head = inChain(orgId) { headOf(chain, orgId) }
        while (true) {
            val event = build(head?.hash, (head?.sequence ?: 0L) + 1)
            head = inChain(orgId) { appendOnHead(chain, orgId, event) }
            if (head.sequence == event.sequence && head.hash == event.hash) return event
        }
    }

    /** An organization's chain is written in the organization ([JdbcDatabase.inOrg]), the account-level one outside. */
    private suspend fun <T> inChain(orgId: Uuid?, block: JdbcTransaction.() -> T): T =
        if (orgId == null) database.query(block) else database.inOrg(orgId, block)

    override suspend fun list(orgId: Uuid?, actorId: Uuid?, beforeSequence: Long?, limit: Int): List<AuditEvent> {
        val before = beforeSequence ?: Long.MAX_VALUE
        if (orgId == null) {
            // The account-level chain is everybody's: it is only ever read for one actor (index on actor_id).
            val actor = requireNotNull(actorId) { "The account-level audit log is read for one user." }
            return database.query {
                t.select(t.read)
                    .where { t.orgId.isNull() and (t.actorId eq actor) and (t.sequence less before) }
                    .orderBy(t.sequence, SortOrder.DESC)
                    .limit(limit)
                    .map { it.toEvent() }
            }
        }
        return database.inOrg(orgId) {
            t.select(t.read)
                .where { (t.chain eq orgId.toString()) and (t.orgId eq orgId) and (t.sequence less before) }
                .orderBy(t.sequence, SortOrder.DESC)
                .limit(limit)
                .map { it.toEvent() }
        }
    }

    override suspend fun listOrg(orgId: Uuid, query: OrgAuditQuery): List<ChainedAuditEvent> {
        val page = OrgAuditPage(orgId, query)
        return database.inOrg(orgId) {
            page.query().map { row -> ChainedAuditEvent(row.toEvent(), page.predecessorHash(row)) }
        }
    }
}

/** What a read of an event selects. */
private val IdentityAuditEvents.read
    get() = listOf(
        id, sequence, orgId, actorId, action, targetType, targetId, details, occurredAt, previousHash, hash, signature,
        keyId,
    )

private fun ResultRow.toEvent(): AuditEvent {
    val t = IdentityAuditEvents
    return AuditEvent(
        id = this[t.id],
        sequence = this[t.sequence],
        entry = AuditEntry(
            orgId = this[t.orgId],
            actorId = this[t.actorId],
            action = this[t.action],
            targetType = this[t.targetType],
            targetId = this[t.targetId],
            details = DETAILS_JSON.decodeFromString(DETAILS, this[t.details]),
        ),
        occurredAt = this[t.occurredAt],
        previousHash = this[t.previousHash],
        hash = this[t.hash],
        signature = this[t.signature],
        keyId = this[t.keyId],
    )
}

/**
 * The read of `GET /api/v1/audit`: an organization's lines, newest first, each with the hash of the line before it in
 * the chain (`sequence - 1`) whatever the filters, so its link can be checked. That hash comes from a `LATERAL` read of
 * the nearest earlier line of the same chain (`q.sequence < sequence ORDER BY q.sequence DESC LIMIT 1`), run only for
 * the lines of the page, each one step backwards in the unique index `(chain, sequence)`; it counts only if that line
 * is `sequence - 1` ([predecessorHash]), so a gap gives no predecessor. A join let the planner read and materialize the
 * whole chain for every page (1.5 s at p95 over 10 000 lines, IdentityReadsBenchTest). An equality on `sequence - 1`
 * reached the index on the PostgreSQL 17 image of 2026-11-09 only: under row-level security, 17.12 (image of
 * 2026-11-13) leaves that computed comparison out of the index condition and scans the table for each line of the
 * page, while a plain `<` between the two columns stays an index condition. Only the filters asked for are in the
 * SQL, each value bound.
 *
 * @param orgId The organization.
 * @param filters The filters, the page's cursor and its size.
 */
internal class OrgAuditPage(private val orgId: Uuid, private val filters: OrgAuditQuery) {
    private val t = IdentityAuditEvents
    private val q = t.alias("q")
    private val prior = q.select(q[t.sequence], q[t.hash])
        .where { (q[t.chain] eq t.chain) and (q[t.sequence] less t.sequence) }
        .orderBy(q[t.sequence], SortOrder.DESC)
        .limit(1)
        .alias("p")
    private val priorSequence = prior[q[t.sequence]]
    private val priorHash = prior[q[t.hash]]

    /** The page's query, to run in the organization. */
    fun query(): Query {
        var where: Op<Boolean> = (t.chain eq orgId.toString()) and (t.orgId eq orgId) and
            (t.sequence less (filters.beforeSequence ?: Long.MAX_VALUE))
        filters.actorId?.let { actor -> where = where and (t.actorId eq actor) }
        filters.action?.let { action -> where = where and (t.action eq action) }
        return t.join(prior, JoinType.LEFT, lateral = true, additionalConstraint = { Op.TRUE })
            .select(t.read + listOf(priorSequence, priorHash))
            .where { where }
            .orderBy(t.sequence, SortOrder.DESC)
            .limit(filters.limit)
    }

    /**
     * The hash of the line just before [row]'s in its chain, or null when that line is missing (a gap, or the first
     * line: the left join then reads nulls, hence `getOrNull`).
     */
    fun predecessorHash(row: ResultRow): String? =
        row.getOrNull(priorHash).takeIf { row.getOrNull(priorSequence) == row[t.sequence] - 1 }
}

/** The last event of a chain: what the next one is chained to. */
private class Head(val sequence: Long, val hash: String)

/** The head of [chain], null while it is empty; [orgId] is the chain's organization, null for the account's. */
private fun JdbcTransaction.headOf(chain: String, orgId: Uuid?): Head? {
    val t = IdentityAuditEvents
    val ofChain = if (orgId == null) t.orgId.isNull() else t.orgId eq orgId
    return t.select(t.sequence, t.hash)
        .where { (t.chain eq chain) and ofChain }
        .orderBy(t.sequence, SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.let { Head(it[t.sequence], it[t.hash]) }
}

/**
 * Appends [event] to [chain] if it is chained to the chain's head, inside this Exposed transaction, as
 * [Transaction.appendAudit] does in a sqlx4k one: the same advisory lock on the chain ([lockUntilCommit]) until the
 * transaction ends, the same read of its head, so two writers on either pool never give two events the same sequence
 * nor fork the chain.
 *
 * @param chain An organization's id, or the account-level chain.
 * @param orgId The chain's organization, null for the account-level chain.
 * @param event The event, built on the head read before this transaction.
 *
 * @return The chain's head after this transaction: [event] if it was appended, else the head it was not chained to.
 */
private fun JdbcTransaction.appendOnHead(chain: String, orgId: Uuid?, event: AuditEvent): Head {
    val t = IdentityAuditEvents
    lockUntilCommit(chain)
    val head = headOf(chain, orgId)
    // The hash of an event covers its sequence: chained to the head's hash, it is the head's next.
    if (head != null && head.hash != event.previousHash) return head
    t.insert {
        it[id] = event.id
        it[this.chain] = chain
        it[sequence] = event.sequence
        it[this.orgId] = event.entry.orgId
        it[actorId] = event.entry.actorId
        it[action] = event.entry.action
        it[targetType] = event.entry.targetType
        it[targetId] = event.entry.targetId
        it[details] = DETAILS_JSON.encodeToString(DETAILS, event.entry.details)
        it[occurredAt] = event.occurredAt
        it[previousHash] = event.previousHash
        it[hash] = event.hash
        it[signature] = event.signature
        it[keyId] = event.keyId
    }
    return Head(event.sequence, event.hash)
}

/**
 * Appends an event to [chain] inside this sqlx4k transaction: kept if and only if the transaction commits. Serializes
 * the appends of one chain until the transaction ends. For the modules still on sqlx4k ([TransactionalAuditLog]),
 * until they move to Exposed; it locks and reads the chain as the Exposed writer of [AuditRepositoryImpl] does.
 *
 * @param chain An organization's id, or the account-level chain.
 * @param build Builds the event from the previous event's hash (null for the first) and the next sequence.
 */
internal suspend fun Transaction.appendAudit(
    chain: String,
    build: suspend (previousHash: String?, sequence: Long) -> AuditEvent,
): AuditEvent {
    // Serializes the appends of one chain until the transaction ends: no two events get the same sequence.
    lockUntilCommit(chain)
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
