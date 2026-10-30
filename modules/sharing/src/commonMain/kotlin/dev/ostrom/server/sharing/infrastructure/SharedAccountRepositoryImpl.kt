package dev.ostrom.server.sharing.infrastructure

import dev.ostrom.server.sharing.domain.AccountLimits
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.AccountStatus
import dev.ostrom.server.sharing.domain.QuotaWindow
import dev.ostrom.server.sharing.domain.SharedAccount
import dev.ostrom.server.sharing.domain.SharedAccountRepository
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
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [SharedAccountRepository] on PostgreSQL ([SharingMigrations]).
 *
 * @param driver The database.
 */
class SharedAccountRepositoryImpl(private val driver: Driver) : SharedAccountRepository {
    override suspend fun upsert(account: SharedAccount, envelope: String): SharedAccount = driver.fetchAll(
        Statement.create(
            "INSERT INTO sharing_accounts (id, org_id, provider, label, email, provider_account_id, owner_member_id, priority, status, " +
                "max_concurrent, utilization_ceiling, monthly_budget_micros, allowed_members, cooldown_until, envelope, created_at) " +
                "VALUES (:id, :org_id, :provider, :label, :email, :provider_account_id, :owner, :priority, :status, :max_concurrent, " +
                ":ceiling, :budget, :allowed, NULL, :envelope, :created_at) " +
                "ON CONFLICT (org_id, provider, provider_account_id) DO UPDATE SET label = EXCLUDED.label, email = EXCLUDED.email, " +
                "status = EXCLUDED.status, max_concurrent = EXCLUDED.max_concurrent, utilization_ceiling = EXCLUDED.utilization_ceiling, " +
                "monthly_budget_micros = EXCLUDED.monthly_budget_micros, allowed_members = EXCLUDED.allowed_members, " +
                "cooldown_until = NULL, envelope = EXCLUDED.envelope RETURNING $COLUMNS",
        )
            .bind("id", account.id)
            .bind("org_id", account.orgId)
            .bind("provider", account.provider.name)
            .bind("label", account.label)
            .bindNullable("email", account.email, String::class)
            .bind("provider_account_id", account.providerAccountId)
            .bindNullable("owner", account.ownerMemberId, Uuid::class)
            .bind("priority", account.priority)
            .bind("status", account.status.name)
            .bindLimits(account.limits)
            .bind("envelope", envelope)
            .bind("created_at", account.createdAt),
    ).getOrThrow().single().toAccount()

    override suspend fun get(orgId: Uuid, id: Uuid): SharedAccount? = driver.fetchAll(
        Statement.create("SELECT $COLUMNS FROM sharing_accounts WHERE org_id = :org_id AND id = :id").bind("org_id", orgId).bind("id", id),
    ).getOrThrow().firstOrNull()?.toAccount()

    override suspend fun list(orgId: Uuid): List<SharedAccount> = driver.fetchAll(
        Statement.create("SELECT $COLUMNS FROM sharing_accounts WHERE org_id = :org_id ORDER BY priority DESC, label, id LIMIT 1000")
            .bind("org_id", orgId),
    ).getOrThrow().map { it.toAccount() }

    override suspend fun ready(orgId: Uuid, provider: AccountProvider): List<SharedAccount> = driver.fetchAll(
        Statement.create(
            "SELECT $COLUMNS FROM sharing_accounts WHERE org_id = :org_id AND provider = :provider AND status = 'READY' " +
                "ORDER BY priority DESC, id LIMIT 1000",
        ).bind("org_id", orgId).bind("provider", provider.name),
    ).getOrThrow().map { it.toAccount() }

    override suspend fun update(account: SharedAccount): SharedAccount? = driver.fetchAll(
        Statement.create(
            "UPDATE sharing_accounts SET label = :label, priority = :priority, status = :status, max_concurrent = :max_concurrent, " +
                "utilization_ceiling = :ceiling, monthly_budget_micros = :budget, allowed_members = :allowed " +
                "WHERE org_id = :org_id AND id = :id RETURNING $COLUMNS",
        )
            .bind("org_id", account.orgId)
            .bind("id", account.id)
            .bind("label", account.label)
            .bind("priority", account.priority)
            .bind("status", account.status.name)
            .bindLimits(account.limits),
    ).getOrThrow().firstOrNull()?.toAccount()

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = driver.execute(
        Statement.create("DELETE FROM sharing_accounts WHERE org_id = :org_id AND id = :id").bind("org_id", orgId).bind("id", id),
    ).getOrThrow() == 1L

    override suspend fun envelope(orgId: Uuid, id: Uuid): String? = driver.fetchAll(
        Statement.create("SELECT envelope FROM sharing_accounts WHERE org_id = :org_id AND id = :id").bind("org_id", orgId).bind("id", id),
    ).getOrThrow().firstOrNull()?.get("envelope")?.asString()

    override suspend fun putEnvelope(orgId: Uuid, id: Uuid, envelope: String) {
        driver.execute(
            Statement.create("UPDATE sharing_accounts SET envelope = :envelope WHERE org_id = :org_id AND id = :id")
                .bind("org_id", orgId).bind("id", id).bind("envelope", envelope),
        ).getOrThrow()
    }

    override suspend fun setStatus(orgId: Uuid, id: Uuid, status: AccountStatus) {
        driver.execute(
            Statement.create("UPDATE sharing_accounts SET status = :status WHERE org_id = :org_id AND id = :id")
                .bind("org_id", orgId).bind("id", id).bind("status", status.name),
        ).getOrThrow()
    }

    override suspend fun setCooldown(orgId: Uuid, id: Uuid, until: Instant?) {
        driver.execute(
            Statement.create("UPDATE sharing_accounts SET cooldown_until = :until WHERE org_id = :org_id AND id = :id")
                .bind("org_id", orgId).bind("id", id).bindNullable("until", until, Instant::class),
        ).getOrThrow()
    }

    override suspend fun putWindows(orgId: Uuid, id: Uuid, windows: List<QuotaWindow>) {
        windows.forEach { window ->
            driver.execute(
                Statement.create(
                    "INSERT INTO sharing_windows (org_id, account_id, name, utilization, resets_at, observed_at) " +
                        "SELECT org_id, id, :name, :utilization, :resets_at, :observed_at FROM sharing_accounts " +
                        "WHERE org_id = :org_id AND id = :id " +
                        "ON CONFLICT (account_id, name) DO UPDATE SET utilization = EXCLUDED.utilization, " +
                        "resets_at = EXCLUDED.resets_at, observed_at = EXCLUDED.observed_at",
                )
                    .bind("org_id", orgId)
                    .bind("id", id)
                    .bind("name", window.window)
                    .bind("utilization", window.utilization)
                    .bindNullable("resets_at", window.resetsAt, Instant::class)
                    .bind("observed_at", window.observedAt),
            ).getOrThrow()
        }
    }

    override suspend fun windows(orgId: Uuid): Map<Uuid, List<QuotaWindow>> = driver.fetchAll(
        Statement.create("SELECT account_id, name, utilization, resets_at, observed_at FROM sharing_windows WHERE org_id = :org_id LIMIT 10000")
            .bind("org_id", orgId),
    ).getOrThrow().map { row ->
        row.get("account_id").asUuid() to QuotaWindow(
            window = row.get("name").asString(),
            utilization = row.get("utilization").asDouble(),
            resetsAt = row.get("resets_at").asInstantOrNull(),
            observedAt = row.get("observed_at").asInstant(),
        )
    }.groupBy({ it.first }, { it.second })

    private fun Statement.bindLimits(limits: AccountLimits): Statement = bind("max_concurrent", limits.maxConcurrent)
        .bind("ceiling", limits.utilizationCeiling)
        .bindNullable("budget", limits.monthlyBudgetMicros, Long::class)
        .bindNullable("allowed", limits.allowedMembers?.let { members -> json.encodeToString(MEMBERS, members.map { it.toString() }.toSet()) }, String::class)

    private fun Statement.bindNullable(name: String, value: Any?, type: KClass<*>): Statement =
        if (value == null) bindNull(name, type) else bind(name, value)

    private fun ResultSet.Row.toAccount() = SharedAccount(
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
            allowedMembers = get("allowed_members").asStringOrNull()?.let { text -> json.decodeFromString(MEMBERS, text).map(Uuid::parse).toSet() },
        ),
        cooldownUntil = get("cooldown_until").asInstantOrNull(),
        createdAt = get("created_at").asInstant(),
    )

    private companion object {
        val json = Json
        val MEMBERS = SetSerializer(String.serializer())
        const val COLUMNS = "id, org_id, provider, label, email, provider_account_id, owner_member_id, priority, status, max_concurrent, " +
            "utilization_ceiling, monthly_budget_micros, allowed_members, cooldown_until, created_at"
    }
}
