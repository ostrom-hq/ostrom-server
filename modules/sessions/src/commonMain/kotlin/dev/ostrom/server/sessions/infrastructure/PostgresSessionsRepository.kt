// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.infrastructure

import dev.ostrom.server.sessions.domain.models.SeenItem
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.models.SessionQuery
import dev.ostrom.server.sessions.domain.repositories.SessionsRepository
import dev.ostrom.server.sessions.domain.repositories.SessionsTransaction
import dev.ostrom.server.sessions.domain.repositories.StaleSession
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [SessionsRepository] on PostgreSQL (sqlx4k). Every transaction first sets `ostrom.org_id`, which the row level
 * security policy reads: a query never sees or writes another organization's rows, even if its `WHERE` forgot it.
 *
 * @param driver The connection pool.
 */
class PostgresSessionsRepository(private val driver: Driver) : SessionsRepository {
    override suspend fun <T> transaction(orgId: Uuid, block: suspend SessionsTransaction.() -> T): T =
        scoped(orgId, block)

    private suspend fun <T> scoped(orgId: Uuid, block: suspend Tx.() -> T): T =
        driver.transaction { Tx(this, orgId).also { it.scope() }.block() }

    override suspend fun get(orgId: Uuid, id: String): Session? = scoped(orgId) {
        select("id = :id", lock = false) { bind("id", id) }.firstOrNull()
    }

    // The unique (org_id, root_id, segment) index serves it.
    override suspend fun last(orgId: Uuid, rootId: String): Session? = scoped(orgId) {
        select("root_id = :root ORDER BY segment DESC LIMIT 1", lock = false) { bind("root", rootId) }.firstOrNull()
    }

    override suspend fun list(orgId: Uuid, query: SessionQuery): List<Session> = scoped(orgId) {
        val where = buildList {
            add("org_id = :org")
            if (query.repo != null) add("repo = :repo")
            if (query.dev != null) add("dev = :dev")
            if (query.open != null) add(if (query.open) "ended_at IS NULL" else "ended_at IS NOT NULL")
            if (query.launchId != null) add("launch_id = :launch")
            if (query.before != null) {
                add(
                    "(started_at, id) < (SELECT started_at, id FROM sessions_sessions " +
                        "WHERE org_id = :org AND id = :before)",
                )
            }
        }.joinToString(" AND ")
        fetch(
            Statement.create(
                "SELECT $COLUMNS FROM sessions_sessions WHERE $where ORDER BY started_at DESC, id DESC LIMIT :limit",
            ).bind("org", orgId).bind("limit", query.limit).apply {
                query.repo?.let { bind("repo", it) }
                query.dev?.let { bind("dev", it) }
                query.launchId?.let { bind("launch", it) }
                query.before?.let { bind("before", it) }
            },
        )
    }

    // The one read across organizations, through the function of SessionsMigrations version 2.
    override suspend fun stale(seenBefore: Instant, limit: Int): List<StaleSession> = driver.fetchAll(
        Statement.create(
            "SELECT org_id, id, last_seen_at FROM " +
                "sessions_stale(CAST(:cutoff AS TIMESTAMPTZ), CAST(:limit AS INTEGER))",
        ).bind("cutoff", seenBefore).bind("limit", limit),
    ).getOrThrow().map {
        StaleSession(it.get("org_id").asUuid(), it.get("id").asString(), it.get("last_seen_at").asInstant())
    }

    private class Tx(private val executor: QueryExecutor, private val orgId: Uuid) : SessionsTransaction {
        suspend fun scope() {
            executor.fetchAll(
                Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgId.toString()),
            ).getOrThrow()
        }

        suspend fun fetch(statement: Statement): List<Session> =
            executor.fetchAll(statement).getOrThrow().map { it.toSession() }

        suspend fun select(where: String, lock: Boolean, binds: Statement.() -> Statement): List<Session> = fetch(
            Statement.create(
                "SELECT $COLUMNS FROM sessions_sessions WHERE org_id = :org AND $where" +
                    if (lock) " FOR UPDATE" else "",
            ).bind("org", orgId).binds(),
        )

        override suspend fun lockRoot(rootId: String) {
            executor.fetchAll(
                Statement.create("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .bind("key", "sessions:$orgId:$rootId"),
            ).getOrThrow()
        }

        override suspend fun segments(rootId: String): List<Session> =
            select("root_id = :root ORDER BY segment", lock = true) { bind("root", rootId) }

        override suspend fun lock(id: String): Session? =
            select("id = :id", lock = true) { bind("id", id) }.firstOrNull()

        override suspend fun insert(session: Session) {
            executor.execute(
                Statement.create("INSERT INTO sessions_sessions ($COLUMNS) VALUES ($VALUES)").bindSession(session),
            ).getOrThrow()
        }

        override suspend fun update(session: Session) {
            val sets = COLUMN_NAMES.filter { it != "org_id" && it != "id" }.joinToString(", ") { "$it = :$it" }
            executor.execute(
                Statement.create("UPDATE sessions_sessions SET $sets WHERE org_id = :org_id AND id = :id")
                    .bindSession(session),
            ).getOrThrow()
        }
    }

    @Serializable
    private data class SeenRecord(val kind: String, val name: String, val via: String, val at: String)

    private companion object {
        val COLUMN_NAMES = listOf(
            "org_id", "id", "root_id", "segment", "resumed_from", "previous_receipt_id", "launch_id", "repo",
            "real_repo", "cwd", "agent", "dev", "harness_version", "package_digest", "model", "source", "started_at",
            "ended_at", "last_seen_at", "receipt_id", "tools", "cost_usd", "exit_code", "seen",
        )
        val COLUMNS = COLUMN_NAMES.joinToString(", ")
        val VALUES = COLUMN_NAMES.joinToString(", ") { ":$it" }
        val json = Json { ignoreUnknownKeys = true }
        val seenList = ListSerializer(SeenRecord.serializer())

        fun Statement.bindOrNull(name: String, value: Any?, type: KClass<*>): Statement =
            if (value == null) bindNull(name, type) else bind(name, value)

        fun Statement.bindSession(session: Session): Statement = bind("org_id", session.orgId)
            .bind("id", session.id)
            .bind("root_id", session.rootId)
            .bind("segment", session.segment)
            .bindOrNull("resumed_from", session.resumedFrom, String::class)
            .bindOrNull("previous_receipt_id", session.previousReceiptId, String::class)
            .bindOrNull("launch_id", session.launchId, String::class)
            .bindOrNull("repo", session.repo, String::class)
            .bindOrNull("real_repo", session.realRepo, String::class)
            .bindOrNull("cwd", session.cwd, String::class)
            .bind("agent", session.agent)
            .bind("dev", session.dev)
            .bindOrNull("harness_version", session.harnessVersion, String::class)
            .bindOrNull("package_digest", session.packageDigest, String::class)
            .bindOrNull("model", session.model, String::class)
            .bindOrNull("source", session.source, String::class)
            .bind("started_at", session.startedAt)
            .bindOrNull("ended_at", session.endedAt, Instant::class)
            .bindOrNull("last_seen_at", session.lastSeenAt, Instant::class)
            .bindOrNull("receipt_id", session.receiptId, String::class)
            .bindOrNull("tools", session.tools, Int::class)
            .bindOrNull("cost_usd", session.costUsd, Double::class)
            .bindOrNull("exit_code", session.exitCode, Int::class)
            .bind("seen", json.encodeToString(seenList, session.seen.map { it.toRecord() }))

        fun SeenItem.toRecord() = SeenRecord(kind, name, via, at.toString())

        fun SeenRecord.toItem() = SeenItem(kind, name, via, Instant.parse(at))

        fun ResultSet.Row.toSession() = Session(
            orgId = get("org_id").asUuid(),
            id = get("id").asString(),
            rootId = get("root_id").asString(),
            segment = get("segment").asString().toInt(),
            resumedFrom = get("resumed_from").asStringOrNull(),
            previousReceiptId = get("previous_receipt_id").asStringOrNull(),
            launchId = get("launch_id").asStringOrNull(),
            repo = get("repo").asStringOrNull(),
            realRepo = get("real_repo").asStringOrNull(),
            cwd = get("cwd").asStringOrNull(),
            agent = get("agent").asString(),
            dev = get("dev").asString(),
            harnessVersion = get("harness_version").asStringOrNull(),
            packageDigest = get("package_digest").asStringOrNull(),
            model = get("model").asStringOrNull(),
            source = get("source").asStringOrNull(),
            startedAt = get("started_at").asInstant(),
            endedAt = get("ended_at").asInstantOrNull(),
            lastSeenAt = get("last_seen_at").asInstantOrNull(),
            receiptId = get("receipt_id").asStringOrNull(),
            tools = get("tools").asStringOrNull()?.toInt(),
            costUsd = get("cost_usd").asStringOrNull()?.toDouble(),
            exitCode = get("exit_code").asStringOrNull()?.toInt(),
            seen = json.decodeFromString(seenList, get("seen").asString()).map { it.toItem() },
        )
    }
}
