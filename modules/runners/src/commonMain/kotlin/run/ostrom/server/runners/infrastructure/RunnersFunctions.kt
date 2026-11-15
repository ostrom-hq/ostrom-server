// SPDX-License-Identifier: Apache-2.0
@file:Suppress("TooManyFunctions") // One table or function wrapper per router function.

package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.appendTo
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The functions of `ostrom_router` the module calls across organizations (RunnersMigrations versions 3, 4, 15, 17, 18,
// 19, 21, 22 and 24), their arguments bound by Exposed. They run as the serving role, outside an organization's
// transaction: each returns ids and organizations only, and what it names is then read in its own organization.

/** What `runners_session_by_token(hash)` returns, read as a table: the session a runner token opens. */
internal object RunnersSessionByToken : Table("runners_session_by_token") {
    val sessionId = uuid("session_id")
    val orgId = uuid("org_id")

    /** The function called for the hash of a runner token. */
    fun of(hash: String): ColumnSet = FunctionCall(tableName, listOf(textValue(hash)))
}

/** What `runners_claim_due(owner, until, now, limit)` returns, read as a table: the sessions it claimed. */
internal object RunnersClaimDue : Table("runners_claim_due") {
    val sessionId = uuid("session_id")
    val orgId = uuid("org_id")

    /** The function called: [owner] claims at most [limit] sessions due at [now] until [until]. */
    fun of(owner: String, until: Instant, now: Instant, limit: Int): ColumnSet =
        FunctionCall(tableName, listOf(textValue(owner), instantValue(until), instantValue(now), intValue(limit)))
}

/** What `runners_sessions_on_device(device, live)` returns, read as a table: the sessions a device hosts. */
internal object RunnersSessionsOnDevice : Table("runners_sessions_on_device") {
    val sessionId = uuid("session_id")
    val orgId = uuid("org_id")

    /** The function called for [device]; [live] keeps the sessions not over. */
    fun of(device: Uuid, live: Boolean): ColumnSet = FunctionCall(
        tableName,
        listOf(QueryParameter(device, UuidColumnType()), QueryParameter(live, BooleanColumnType())),
    )
}

/** What `runners_sessions_without_profile(after, limit)` returns, read as a table: a page of sessions. */
internal object RunnersSessionsWithoutProfile : Table("runners_sessions_without_profile") {
    val sessionId = uuid("session_id")
    val orgId = uuid("org_id")

    /** The function called for the page after [after] (the first page when null), at most [limit] sessions. */
    fun of(after: Uuid?, limit: Int): ColumnSet = FunctionCall(
        tableName,
        listOf(if (after == null) NullUuid else QueryParameter(after, UuidColumnType()), intValue(limit)),
    )
}

/** What `runners_registered_key(id)` returns, read as a table: a runner's organization and key hash. */
internal object RunnersRegisteredKey : Table("runners_registered_key") {
    val orgId = uuid("org_id")
    val keyHash = text("key_hash")

    /** The function called for the runner [id]. */
    fun of(id: Uuid): ColumnSet = FunctionCall(tableName, listOf(QueryParameter(id, UuidColumnType())))
}

/** What `runners_cluster_live_objects(cluster)` returns, read as a table: the sessions of a cluster not over. */
internal object RunnersClusterLiveObjects : Table("runners_cluster_live_objects") {
    val sessionId = uuid("session_id")
    val epoch = long("epoch")
    val state = text("state")
    val profile = text("profile").nullable()
    val claimDeleted = bool("claim_deleted")

    /** The function called for [cluster]. */
    fun of(cluster: String): ColumnSet = FunctionCall(tableName, listOf(textValue(cluster)))
}

/** What `runners_stale_inference_credentials(limit)` returns, read as a table: the credentials to revoke. */
internal object RunnersStaleInferenceCredentials : Table("runners_stale_inference_credentials") {
    val orgId = uuid("org_id")
    val tokenId = uuid("token_id")

    /** The function called, at most [limit] credentials. */
    fun of(limit: Int): ColumnSet = FunctionCall(tableName, listOf(intValue(limit)))
}

/** What `runners_files_deleting(limit)` returns, read as a table: the files whose bytes the sweep removes. */
internal object RunnersFilesDeleting : Table("runners_files_deleting") {
    val orgId = uuid("org_id")
    val id = uuid("id")

    /** The function called, at most [limit] files. */
    fun of(limit: Int): ColumnSet = FunctionCall(tableName, listOf(intValue(limit)))
}

/** What `runners_conversation_wakes(limit)` returns, read as a table: the sessions with a message to wake for. */
internal object RunnersConversationWakes : Table("runners_conversation_wakes") {
    val sessionId = uuid("session_id")
    val orgId = uuid("org_id")
    val inputId = uuid("input_id")

    /** The function called, at most [limit] sessions. */
    fun of(limit: Int): ColumnSet = FunctionCall(tableName, listOf(intValue(limit)))
}

/** What `runners_conversation_ended(limit)` returns, read as a table: the ended sessions with pending inputs. */
internal object RunnersConversationEnded : Table("runners_conversation_ended") {
    val sessionId = uuid("session_id")
    val orgId = uuid("org_id")

    /** The function called, at most [limit] sessions. */
    fun of(limit: Int): ColumnSet = FunctionCall(tableName, listOf(intValue(limit)))
}

/** `runners_session_org(id)`: the organization of a session, null when it has none. */
internal fun sessionOrg(id: Uuid): CustomFunction<Uuid?> = CustomFunction(
    "runners_session_org",
    UuidColumnType().apply { nullable = true },
    QueryParameter(id, UuidColumnType()),
)

/** `runners_purge_create_keys()`: deletes a batch of the expired idempotency records and returns how many. */
internal fun purgeCreateKeys(): CustomFunction<Long> = CustomFunction("runners_purge_create_keys", LongColumnType())

/** `runners_release(id, owner, hasNext, nextAt)`: releases a claim, and keeps a request for an earlier look. */
internal fun releaseClaim(id: Uuid, owner: String, nextAt: Instant?): CustomFunction<String> = CustomFunction(
    "runners_release",
    TextColumnType(),
    QueryParameter(id, UuidColumnType()),
    textValue(owner),
    QueryParameter(nextAt != null, BooleanColumnType()),
    instantValue(nextAt ?: EPOCH),
)

/** `runners_files_expire(now, limit)`: marks a batch of the expired files for deletion and returns how many. */
internal fun expireFiles(now: Instant, limit: Int): CustomFunction<Long> =
    CustomFunction("runners_files_expire", LongColumnType(), instantValue(now), intValue(limit))

/** `runners_file_usage_purge(day)`: deletes a batch of the counters before [day] and returns how many. */
internal fun purgeFileUsage(day: Long): CustomFunction<Long> =
    CustomFunction("runners_file_usage_purge", LongColumnType(), QueryParameter(day, LongColumnType()))

/** `runners_conversation_purge(before, limit)`: deletes a batch of old events and inputs and returns how many. */
internal fun purgeConversation(before: Instant, limit: Int): CustomFunction<Long> =
    CustomFunction("runners_conversation_purge", LongColumnType(), instantValue(before), intValue(limit))

/** The one value of a function [call] that returns a scalar (`SELECT name(args…)`). */
internal fun <T> JdbcTransaction.scalar(call: CustomFunction<T>): T = Table.Dual.select(call).single()[call]

/** A placeholder for an unused timestamp argument (PostgreSQL cannot read Instant.DISTANT_PAST). */
private val EPOCH: Instant = Instant.fromEpochSeconds(0)

/** A NULL read as a UUID, whatever the driver binds a null as. */
private object NullUuid : Expression<Uuid?>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder { append("CAST(NULL AS UUID)") }
}

/** A set-returning function called with its [arguments], read as a table of its own name. */
private class FunctionCall(name: String, private val arguments: List<Expression<*>>) : Table(name) {
    override fun describe(s: Transaction, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append(tableName)
        arguments.appendTo(this, prefix = "(", postfix = ")") { +it }
    }
}

private fun textValue(value: String): Expression<String> = QueryParameter(value, TextColumnType())

private fun intValue(value: Int): Expression<Int> = QueryParameter(value, IntegerColumnType())

private fun instantValue(value: Instant): Expression<Instant> = QueryParameter(value, InstantColumnType())

/** `GREATEST(column, value)`: the later of a time and a stored one; NULL is skipped, so a NULL column gives [value]. */
internal fun greatest(column: Expression<Instant?>, value: Instant?): Expression<Instant> =
    CustomFunction("GREATEST", InstantColumnType(), column, nullableInstant(value))

/** `LEAST(column, value)`: the earlier of a time and a stored one; NULL is skipped, so a NULL column gives [value]. */
internal fun least(column: Expression<Instant?>, value: Instant): Expression<Instant> =
    CustomFunction("LEAST", InstantColumnType(), column, instantValue(value))

private fun nullableInstant(value: Instant?): Expression<Instant?> =
    QueryParameter(value, InstantColumnType().apply { nullable = true })
