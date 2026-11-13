// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.GitHubInstallationOrigin
import run.ostrom.server.integrations.github.domain.GitHubOriginsRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * [GitHubOriginsRepository] on PostgreSQL. Who installed an installation is read and written in a transaction that
 * names that installation, the key its row level security asks for; the deliveries are the instance's (an id and a
 * time, nothing else), without row level security, like the instance's App.
 *
 * @param driver The connection pool.
 */
class PostgresGitHubOriginsRepository(private val driver: Driver) : GitHubOriginsRepository {
    override suspend fun saveOrigin(origin: GitHubInstallationOrigin, at: Instant) {
        driver.transaction {
            fetchAll(installation(origin.installationId)).getOrThrow()
            execute(
                Statement.create(
                    "INSERT INTO github_installation_origins (installation_id, sender, account, account_type, " +
                        "created_at) VALUES (:id, :sender, :account, :type, :at) ON CONFLICT (installation_id) DO " +
                        "UPDATE SET sender = EXCLUDED.sender, account = EXCLUDED.account, " +
                        "account_type = EXCLUDED.account_type, created_at = EXCLUDED.created_at",
                ).bind("id", origin.installationId).bind("sender", origin.sender).bind("account", origin.account)
                    .bind("type", origin.accountType).bind("at", at),
            ).getOrThrow()
        }
    }

    override suspend fun origin(installationId: Long): GitHubInstallationOrigin? = driver.transaction {
        fetchAll(installation(installationId)).getOrThrow()
        fetchAll(Statement.create(GitHubWebhookQueries.ORIGIN).bind("id", installationId)).getOrThrow().firstOrNull()
            ?.let {
                GitHubInstallationOrigin(
                    installationId = installationId,
                    sender = it.get("sender").asString(),
                    account = it.get("account").asString(),
                    accountType = it.get("account_type").asString(),
                )
            }
    }

    override suspend fun claimDelivery(deliveryId: String, at: Instant): Boolean = driver.transaction {
        val claimed = execute(
            Statement.create(
                "INSERT INTO github_webhook_deliveries (delivery_id, received_at) VALUES (:id, :at) " +
                    "ON CONFLICT (delivery_id) DO NOTHING",
            ).bind("id", deliveryId).bind("at", at),
        ).getOrThrow() == 1L
        // A bounded purge on each claim: the table keeps 30 days of deliveries, whatever the traffic.
        execute(Statement.create(GitHubWebhookQueries.PURGE).bind("cutoff", at - DELIVERY_RETENTION)).getOrThrow()
        claimed
    }

    override suspend fun releaseDelivery(deliveryId: String) {
        driver.transaction {
            val release = Statement.create("DELETE FROM github_webhook_deliveries WHERE delivery_id = :id")
            execute(release.bind("id", deliveryId)).getOrThrow()
        }
    }

    private fun installation(installationId: Long) =
        Statement.create("SELECT set_config('ostrom.github_installation', :value, TRUE)")
            .bind("value", installationId.toString())
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val DELIVERY_RETENTION = 30.days

/** The queries a webhook or an install callback adds, shared with the test that keeps their plans. */
internal object GitHubWebhookQueries {
    const val ORIGIN =
        "SELECT sender, account, account_type FROM github_installation_origins WHERE installation_id = :id"

    // At most 100 deliveries older than 30 days go at each claim, oldest first by the index on `received_at`.
    const val PURGE = "DELETE FROM github_webhook_deliveries WHERE delivery_id IN (SELECT delivery_id FROM " +
        "github_webhook_deliveries WHERE received_at < :cutoff ORDER BY received_at LIMIT 100)"
}
