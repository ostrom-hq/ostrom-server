// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.infrastructure

import dev.ostrom.server.integrations.github.domain.GateCheck
import dev.ostrom.server.integrations.github.domain.GitHubInstallation
import dev.ostrom.server.integrations.github.domain.GitHubInstallationOrigin
import dev.ostrom.server.integrations.github.domain.GitHubRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [GitHubRepository] on PostgreSQL. Every call is one transaction that first sets the one key row level security
 * lets it see: the organization, or the installation of a verified webhook, or the hash of an install state.
 *
 * @param driver The connection pool.
 */
class PostgresGitHubRepository(private val driver: Driver) : GitHubRepository {
    private suspend fun <T> keyed(setting: String, value: String, block: suspend Tx.() -> T): T = driver.transaction {
        val tx = Tx(this)
        tx.fetch(Statement.create("SELECT set_config(:name, :value, TRUE)").bind("name", setting).bind("value", value))
        tx.block()
    }

    private suspend fun <T> scoped(orgId: Uuid, block: suspend Tx.() -> T): T =
        keyed("ostrom.org_id", orgId.toString(), block)

    override suspend fun installation(orgId: Uuid): GitHubInstallation? = scoped(orgId) {
        fetch(
            Statement.create("SELECT $INSTALLATION_COLUMNS FROM github_installations WHERE org_id = :org")
                .bind("org", orgId),
        ).firstOrNull()?.toInstallation()
    }

    private suspend fun <T> installationKeyed(installationId: Long, block: suspend Tx.() -> T): T =
        keyed("ostrom.github_installation", installationId.toString(), block)

    override suspend fun installationById(installationId: Long): GitHubInstallation? =
        installationKeyed(installationId) {
            fetch(
                Statement.create("SELECT $INSTALLATION_COLUMNS FROM github_installations WHERE installation_id = :id")
                    .bind("id", installationId),
            ).firstOrNull()?.toInstallation()
        }

    override suspend fun saveInstallation(installation: GitHubInstallation, at: Instant) {
        scoped(installation.orgId) {
            execute(
                Statement.create(
                    "INSERT INTO github_installations (org_id, installation_id, account, created_at) " +
                        "VALUES (:org, :id, :account, :at) ON CONFLICT (org_id) DO UPDATE SET " +
                        "installation_id = EXCLUDED.installation_id, account = EXCLUDED.account, " +
                        "created_at = EXCLUDED.created_at",
                ).bind("org", installation.orgId).bind("id", installation.installationId)
                    .bind("account", installation.account).bind("at", at),
            )
        }
    }

    override suspend fun forgetInstallation(installationId: Long) {
        installationKeyed(installationId) {
            execute(
                Statement.create("DELETE FROM github_installations WHERE installation_id = :id")
                    .bind("id", installationId),
            )
            execute(
                Statement.create("DELETE FROM github_installation_origins WHERE installation_id = :id")
                    .bind("id", installationId),
            )
        }
    }

    override suspend fun saveOrigin(origin: GitHubInstallationOrigin, at: Instant) {
        installationKeyed(origin.installationId) {
            execute(
                Statement.create(
                    "INSERT INTO github_installation_origins (installation_id, sender, account, account_type, " +
                        "created_at) VALUES (:id, :sender, :account, :type, :at) ON CONFLICT (installation_id) DO " +
                        "UPDATE SET sender = EXCLUDED.sender, account = EXCLUDED.account, " +
                        "account_type = EXCLUDED.account_type, created_at = EXCLUDED.created_at",
                ).bind("id", origin.installationId).bind("sender", origin.sender).bind("account", origin.account)
                    .bind("type", origin.accountType).bind("at", at),
            )
        }
    }

    override suspend fun origin(installationId: Long): GitHubInstallationOrigin? = installationKeyed(installationId) {
        fetch(
            Statement.create(GitHubWebhookQueries.ORIGIN).bind("id", installationId),
        ).firstOrNull()?.let {
            GitHubInstallationOrigin(
                installationId = installationId,
                sender = it.get("sender").asString(),
                account = it.get("account").asString(),
                accountType = it.get("account_type").asString(),
            )
        }
    }

    // Deliveries are the instance's, not an organization's (an id and a time, nothing else): no row level security.
    override suspend fun claimDelivery(deliveryId: String, at: Instant): Boolean = driver.transaction {
        val tx = Tx(this)
        val claimed = tx.execute(
            Statement.create(
                "INSERT INTO github_webhook_deliveries (delivery_id, received_at) VALUES (:id, :at) " +
                    "ON CONFLICT (delivery_id) DO NOTHING",
            ).bind("id", deliveryId).bind("at", at),
        ) == 1L
        // A bounded purge on each claim: the table keeps 30 days of deliveries, whatever the traffic.
        tx.execute(Statement.create(GitHubWebhookQueries.PURGE).bind("cutoff", at - DELIVERY_RETENTION))
        claimed
    }

    override suspend fun releaseDelivery(deliveryId: String) {
        driver.transaction {
            Tx(this).execute(
                Statement.create("DELETE FROM github_webhook_deliveries WHERE delivery_id = :id")
                    .bind("id", deliveryId),
            )
        }
    }

    override suspend fun saveState(orgId: Uuid, stateHash: String, by: String, expiresAt: Instant) {
        scoped(orgId) {
            execute(
                Statement.create(
                    "INSERT INTO github_install_states (state_hash, org_id, created_by, expires_at) " +
                        "VALUES (:hash, :org, :by, :expires)",
                ).bind("hash", stateHash).bind("org", orgId).bind("by", by).bind("expires", expiresAt),
            )
        }
    }

    override suspend fun takeState(stateHash: String, now: Instant): Uuid? = keyed("ostrom.github_state", stateHash) {
        fetch(
            Statement.create(
                "DELETE FROM github_install_states WHERE state_hash = :hash RETURNING org_id, expires_at",
            ).bind("hash", stateHash),
        ).firstOrNull()
            ?.takeIf { it.get("expires_at").asInstant() > now }
            ?.let { Uuid.parse(it.get("org_id").asString()) }
    }

    override suspend fun check(orgId: Uuid, gateId: Uuid): GateCheck? = scoped(orgId) {
        fetch(
            Statement.create(
                "SELECT gate_id, repo, sha, check_run_id, conclusion, updated_at FROM github_checks " +
                    "WHERE org_id = :org AND gate_id = :gate",
            ).bind("org", orgId).bind("gate", gateId),
        ).firstOrNull()?.let {
            GateCheck(
                gateId = Uuid.parse(it.get("gate_id").asString()),
                repo = it.get("repo").asString(),
                sha = it.get("sha").asString(),
                checkRunId = it.get("check_run_id").asString().toLong(),
                conclusion = it.get("conclusion").asStringOrNull(),
                updatedAt = it.get("updated_at").asInstant(),
            )
        }
    }

    override suspend fun saveCheck(orgId: Uuid, check: GateCheck) {
        scoped(orgId) {
            execute(
                Statement.create(
                    "INSERT INTO github_checks (org_id, gate_id, repo, sha, check_run_id, conclusion, updated_at) " +
                        "VALUES (:org, :gate, :repo, :sha, :run, :conclusion, :at) ON CONFLICT (org_id, gate_id) " +
                        "DO UPDATE SET sha = EXCLUDED.sha, check_run_id = EXCLUDED.check_run_id, " +
                        "conclusion = EXCLUDED.conclusion, updated_at = EXCLUDED.updated_at",
                ).bind("org", orgId).bind("gate", check.gateId).bind("repo", check.repo).bind("sha", check.sha)
                    .bind("run", check.checkRunId).bind("conclusion", check.conclusion)
                    .bind("at", check.updatedAt),
            )
        }
    }

    private class Tx(private val executor: QueryExecutor) {
        suspend fun fetch(statement: Statement): List<ResultSet.Row> =
            executor.fetchAll(statement).getOrThrow().toList()

        suspend fun execute(statement: Statement): Long = executor.execute(statement).getOrThrow()
    }

    private companion object {
        const val INSTALLATION_COLUMNS = "org_id, installation_id, account"

        fun ResultSet.Row.toInstallation() = GitHubInstallation(
            orgId = Uuid.parse(get("org_id").asString()),
            installationId = get("installation_id").asString().toLong(),
            account = get("account").asString(),
        )
    }
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
