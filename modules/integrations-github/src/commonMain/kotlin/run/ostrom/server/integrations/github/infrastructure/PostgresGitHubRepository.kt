// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.GateCheck
import run.ostrom.server.integrations.github.domain.GitHubInstallation
import run.ostrom.server.integrations.github.domain.GitHubRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.jdbc.deleteReturning
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [GitHubRepository] on PostgreSQL through Exposed. Every call is one transaction that first sets the one key row
 * level security lets it see: the organization, or the installation of a verified webhook, or the hash of an install
 * state.
 *
 * @param database The serving role's pool.
 */
@Suppress("TooManyFunctions") // One implementation of the port: installations, states and the App's count.
class PostgresGitHubRepository(private val database: JdbcDatabase) : GitHubRepository {
    override suspend fun installation(orgId: Uuid): GitHubInstallation? = database.withOrganization(orgId) {
        val i = GitHubInstallations
        val row = i.select(i.columns).where { i.orgId eq orgId }.firstOrNull()
        if (row == null) null else row.toInstallation()
    }

    override suspend fun installationById(installationId: Long): GitHubInstallation? =
        database.keyed(INSTALLATION_SETTING, installationId.toString()) {
            val i = GitHubInstallations
            val row = i.select(i.columns).where { i.installationId eq installationId }.firstOrNull()
            if (row == null) null else row.toInstallation()
        }

    override suspend fun saveInstallation(installation: GitHubInstallation, at: Instant) {
        database.withOrganization(installation.orgId) {
            GitHubInstallations.upsert(GitHubInstallations.orgId) {
                it[orgId] = installation.orgId
                it[installationId] = installation.installationId
                it[account] = installation.account
                it[createdAt] = at
            }
        }
    }

    // Linked or not, and who installed it (GitHubOriginsRepository's table): one transaction under the installation.
    override suspend fun forgetInstallation(installationId: Long) {
        database.keyed(INSTALLATION_SETTING, installationId.toString()) {
            val i = GitHubInstallations
            val o = GitHubInstallationOrigins
            i.deleteWhere { i.installationId eq installationId }
            o.deleteWhere { o.installationId eq installationId }
        }
    }

    // Across organizations, through the function of `ostrom_router` (docs/tenancy.md).
    override suspend fun installationCount(): Long = database.query { installationsCount() }

    override suspend fun saveState(orgId: Uuid, stateHash: String, by: String, expiresAt: Instant) {
        database.withOrganization(orgId) {
            GitHubInstallStates.insert {
                it[this.orgId] = orgId
                it[this.stateHash] = stateHash
                it[createdBy] = by
                it[this.expiresAt] = expiresAt
            }
        }
    }

    override suspend fun takeState(stateHash: String, now: Instant): Uuid? =
        database.keyed(STATE_SETTING, stateHash) {
            val s = GitHubInstallStates
            val row = s.deleteReturning(listOf(s.orgId, s.expiresAt), where = { s.stateHash eq stateHash })
                .firstOrNull()
            if (row != null && row[s.expiresAt] > now) row[s.orgId] else null
        }

    override suspend fun check(orgId: Uuid, gateId: Uuid): GateCheck? = database.withOrganization(orgId) {
        val c = GitHubChecks
        val row = c.select(c.gateId, c.repo, c.sha, c.checkRunId, c.conclusion, c.updatedAt)
            .where { (c.orgId eq orgId) and (c.gateId eq gateId) }
            .firstOrNull()
        if (row == null) {
            null
        } else {
            GateCheck(
                gateId = row[c.gateId],
                repo = row[c.repo],
                sha = row[c.sha],
                checkRunId = row[c.checkRunId],
                conclusion = row[c.conclusion],
                updatedAt = row[c.updatedAt],
            )
        }
    }

    // A check seen again keeps its repository: only the commit, the run, the conclusion and the time move.
    override suspend fun saveCheck(orgId: Uuid, check: GateCheck) {
        database.withOrganization(orgId) {
            val c = GitHubChecks
            c.upsert(
                c.orgId, c.gateId,
                onUpdate = {
                    it[c.sha] = insertValue(c.sha)
                    it[c.checkRunId] = insertValue(c.checkRunId)
                    it[c.conclusion] = insertValue(c.conclusion)
                    it[c.updatedAt] = insertValue(c.updatedAt)
                },
            ) {
                it[this.orgId] = orgId
                it[gateId] = check.gateId
                it[repo] = check.repo
                it[sha] = check.sha
                it[checkRunId] = check.checkRunId
                it[conclusion] = check.conclusion
                it[updatedAt] = check.updatedAt
            }
        }
    }

    private fun ResultRow.toInstallation() = GitHubInstallation(
        orgId = this[GitHubInstallations.orgId],
        installationId = this[GitHubInstallations.installationId],
        account = this[GitHubInstallations.account],
    )
}
