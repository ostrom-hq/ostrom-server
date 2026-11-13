// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.secrets.infrastructure.inTenant
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.QuotaWindowRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asDouble
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [QuotaWindowRepository] on PostgreSQL ([SharingMigrations]), every query confined to the org it names (S2-01). A
 * window is only written for an account of that org: the insert selects the account row, which another org's
 * confinement hides.
 *
 * @param driver The database.
 */
class QuotaWindowRepositoryImpl(private val driver: Driver) : QuotaWindowRepository {
    override suspend fun put(orgId: Uuid, id: Uuid, windows: List<QuotaWindow>) {
        driver.inTenant(orgId) {
            windows.forEach { window ->
                execute(
                    Statement.create(UPSERT)
                        .bind("org_id", orgId)
                        .bind("id", id)
                        .bind("name", window.window)
                        .bind("utilization", window.utilization)
                        .bindNullable("resets_at", window.resetsAt, Instant::class)
                        .bind("observed_at", window.observedAt),
                ).getOrThrow()
            }
        }
    }

    override suspend fun byAccount(orgId: Uuid): Map<Uuid, List<QuotaWindow>> = driver.fetchInOrg(
        orgId,
        Statement.create(
            "SELECT account_id, name, utilization, resets_at, observed_at FROM sharing_windows " +
                "WHERE org_id = :org_id LIMIT 10000",
        ).bind("org_id", orgId),
    ).getOrThrow().map { row ->
        row.get("account_id").asUuid() to QuotaWindow(
            window = row.get("name").asString(),
            utilization = row.get("utilization").asDouble(),
            resetsAt = row.get("resets_at").asInstantOrNull(),
            observedAt = row.get("observed_at").asInstant(),
        )
    }.groupBy({ it.first }, { it.second })

    private companion object {
        const val UPSERT =
            "INSERT INTO sharing_windows (org_id, account_id, name, utilization, resets_at, observed_at) " +
                "SELECT org_id, id, :name, :utilization, :resets_at, :observed_at FROM sharing_accounts " +
                "WHERE org_id = :org_id AND id = :id " +
                "ON CONFLICT (account_id, name) DO UPDATE SET utilization = EXCLUDED.utilization, " +
                "resets_at = EXCLUDED.resets_at, observed_at = EXCLUDED.observed_at"
    }
}
