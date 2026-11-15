// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.gateway.domain.PacingSettings
import run.ostrom.server.gateway.domain.PacingSettingsRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.uuid.Uuid

/**
 * [PacingSettingsRepository] on PostgreSQL ([GatewayPacingSettings]), confined to the org, with no process-local cache
 * (TeamCode's `SqlPacingSettingsStore`): an update merges under the org's admission lock, so two administrators never
 * overwrite each other and admission never reads a half-written mode.
 *
 * @param database The JDBC pool.
 */
class PacingSettingsRepositoryImpl(private val database: JdbcDatabase) : PacingSettingsRepository {
    override suspend fun get(orgId: Uuid): PacingSettings = database.inOrg(orgId) { readPacingSettings(orgId) }

    override suspend fun update(
        orgId: Uuid,
        observationOnly: Boolean?,
        autoPoolSize: Boolean?,
    ): Pair<PacingSettings, PacingSettings> = database.inOrg(orgId) {
        lockUntilCommit(admissionLockKey(orgId))
        val before = readPacingSettings(orgId)
        val after = PacingSettings(observationOnly ?: before.observationOnly, autoPoolSize ?: before.autoPoolSize)
        GatewayPacingSettings.upsert(GatewayPacingSettings.orgId) {
            it[this.orgId] = orgId
            it[this.observationOnly] = after.observationOnly
            it[this.autoPoolSize] = after.autoPoolSize
        }
        before to after
    }
}

/** The org's settings, the defaults when none was stored; inside the caller's transaction. */
internal fun JdbcTransaction.readPacingSettings(orgId: Uuid): PacingSettings = GatewayPacingSettings.selectAll()
    .where { GatewayPacingSettings.orgId eq orgId }
    .singleOrNull()
    ?.let { PacingSettings(it[GatewayPacingSettings.observationOnly], it[GatewayPacingSettings.autoPoolSize]) }
    ?: PacingSettings()
