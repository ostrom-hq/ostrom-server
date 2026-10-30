package dev.ostrom.server.sharing.infrastructure

import dev.ostrom.server.secrets.infrastructure.executeInOrg
import dev.ostrom.server.sharing.domain.AccountLimits
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.AccountStatus
import dev.ostrom.server.sharing.domain.SharedAccount
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asDouble
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass
import kotlin.uuid.Uuid

/** The columns of an account row, tokens excluded: the envelope is only read by its own query. */
internal const val ACCOUNT_COLUMNS: String =
    "id, org_id, provider, label, email, provider_account_id, owner_member_id, priority, status, max_concurrent, " +
        "utilization_ceiling, monthly_budget_micros, allowed_members, cooldown_until, created_at"

private val MEMBERS = SetSerializer(String.serializer())

/** Sets one column of one account, confined to [orgId]; [value] binds `:value`. */
internal suspend fun Driver.setColumn(orgId: Uuid, id: Uuid, assignment: String, value: Statement.() -> Statement) {
    executeInOrg(
        orgId,
        Statement.create("UPDATE sharing_accounts SET $assignment WHERE org_id = :org_id AND id = :id")
            .bind("org_id", orgId)
            .bind("id", id)
            .value(),
    ).getOrThrow()
}

/** Binds `:max_concurrent`, `:ceiling`, `:budget` and `:allowed` (a JSON array of member ids, or NULL). */
internal fun Statement.bindLimits(limits: AccountLimits): Statement = bind("max_concurrent", limits.maxConcurrent)
    .bind("ceiling", limits.utilizationCeiling)
    .bindNullable("budget", limits.monthlyBudgetMicros, Long::class)
    .bindNullable(
        "allowed",
        limits.allowedMembers?.let { members -> Json.encodeToString(MEMBERS, members.map { it.toString() }.toSet()) },
        String::class,
    )

/** Binds [value], or a typed NULL. */
internal fun Statement.bindNullable(name: String, value: Any?, type: KClass<*>): Statement =
    if (value == null) bindNull(name, type) else bind(name, value)

/** Reads an account row of [ACCOUNT_COLUMNS]. */
internal fun ResultSet.Row.toAccount() = SharedAccount(
    id = get("id").asUuid(),
    orgId = get("org_id").asUuid(),
    provider = AccountProvider.valueOf(get("provider").asString()),
    label = get("label").asString(),
    email = get("email").asStringOrNull(),
    providerAccountId = get("provider_account_id").asString(),
    ownerMemberId = get("owner_member_id").asUuidOrNull(),
    priority = get("priority").asInt(),
    status = AccountStatus.valueOf(get("status").asString()),
    limits = AccountLimits(
        maxConcurrent = get("max_concurrent").asInt(),
        utilizationCeiling = get("utilization_ceiling").asDouble(),
        monthlyBudgetMicros = get("monthly_budget_micros").asLongOrNull(),
        allowedMembers = get("allowed_members").asStringOrNull()?.let { text ->
            Json.decodeFromString(MEMBERS, text).map(Uuid::parse).toSet()
        },
    ),
    cooldownUntil = get("cooldown_until").asInstantOrNull(),
    createdAt = get("created_at").asInstant(),
)
