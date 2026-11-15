// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.GitHubInstallationOrigin
import run.ostrom.server.integrations.github.domain.GitHubOriginsRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.write
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * [GitHubOriginsRepository] on PostgreSQL through Exposed. Who installed an installation is read and written in a
 * transaction that names that installation, the key its row level security asks for; the deliveries are the
 * instance's (an id and a time, nothing else), without row level security, like the instance's App.
 *
 * @param database The serving role's pool.
 */
class PostgresGitHubOriginsRepository(private val database: JdbcDatabase) : GitHubOriginsRepository {
    override suspend fun saveOrigin(origin: GitHubInstallationOrigin, at: Instant) {
        database.keyed(INSTALLATION_SETTING, origin.installationId.toString()) {
            val o = GitHubInstallationOrigins
            o.upsert(o.installationId) {
                it[installationId] = origin.installationId
                it[sender] = origin.sender
                it[account] = origin.account
                it[accountType] = origin.accountType
                it[createdAt] = at
            }
        }
    }

    override suspend fun origin(installationId: Long): GitHubInstallationOrigin? =
        database.keyed(INSTALLATION_SETTING, installationId.toString()) {
            val o = GitHubInstallationOrigins
            val row = originOf(installationId).firstOrNull()
            if (row == null) {
                null
            } else {
                GitHubInstallationOrigin(
                    installationId = installationId,
                    sender = row[o.sender],
                    account = row[o.account],
                    accountType = row[o.accountType],
                )
            }
        }

    override suspend fun claimDelivery(deliveryId: String, at: Instant): Boolean = database.query {
        val d = GitHubWebhookDeliveries
        val claimed = d.insertIgnore {
            it[this.deliveryId] = deliveryId
            it[receivedAt] = at
        }.insertedCount == 1
        // A bounded purge on each claim: the table keeps 30 days of deliveries, whatever the traffic.
        write { purgeDeliveries(at - DELIVERY_RETENTION) }
        claimed
    }

    override suspend fun releaseDelivery(deliveryId: String) {
        database.query {
            val d = GitHubWebhookDeliveries
            d.deleteWhere { d.deliveryId eq deliveryId }
        }
    }
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val DELIVERY_RETENTION = 30.days
private const val PURGE_BATCH = 100

/** Who installed [installationId], the read of a webhook or an install callback, shared with its plan test. */
internal fun originOf(installationId: Long): Query {
    val o = GitHubInstallationOrigins
    return o.select(o.sender, o.account, o.accountType).where { o.installationId eq installationId }
}

/**
 * At most 100 deliveries older than [cutoff] go at each claim, oldest first by the index on `received_at`.
 */
internal fun StatementBuilder.purgeDeliveries(cutoff: Instant): DeleteStatement {
    val d = GitHubWebhookDeliveries
    val old = d.select(d.deliveryId).where { d.receivedAt less cutoff }
        .orderBy(d.receivedAt to SortOrder.ASC).limit(PURGE_BATCH)
    return d.deleteWhere { d.deliveryId inSubQuery old }
}
