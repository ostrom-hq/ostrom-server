// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.infrastructure

import dev.ostrom.server.integrations.github.domain.GateCheck
import dev.ostrom.server.integrations.github.domain.GitHubInstallation
import dev.ostrom.server.integrations.github.domain.GitHubRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
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

    override suspend fun installationById(installationId: Long): GitHubInstallation? =
        keyed("ostrom.github_installation", installationId.toString()) {
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

    override suspend fun removeInstallation(installation: GitHubInstallation) {
        keyed("ostrom.github_installation", installation.installationId.toString()) {
            execute(
                Statement.create("DELETE FROM github_installations WHERE installation_id = :id")
                    .bind("id", installation.installationId),
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

    private fun ResultSet.Row.toInstallation() = GitHubInstallation(
        orgId = Uuid.parse(get("org_id").asString()),
        installationId = get("installation_id").asString().toLong(),
        account = get("account").asString(),
    )

    private class Tx(private val executor: QueryExecutor) {
        suspend fun fetch(statement: Statement): List<ResultSet.Row> =
            executor.fetchAll(statement).getOrThrow().toList()

        suspend fun execute(statement: Statement): Long = executor.execute(statement).getOrThrow()
    }

    private companion object {
        const val INSTALLATION_COLUMNS = "org_id, installation_id, account"
    }
}
