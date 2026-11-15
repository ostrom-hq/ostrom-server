// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.infrastructure

import run.ostrom.server.cloud.domain.CloudSettings
import run.ostrom.server.cloud.domain.CloudSettingsRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.http.OstromJson
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [CloudSettingsRepository] on PostgreSQL through Exposed: one row of `cloud_org_settings` per organization, every
 * statement in its tenant ([JdbcDatabase.inOrg], row-level security).
 *
 * @param database The serving role's pool.
 */
class CloudSettingsRepositoryImpl(private val database: JdbcDatabase) : CloudSettingsRepository {
    override suspend fun get(orgId: Uuid): CloudSettings? = database.inOrg(orgId) {
        val s = CloudOrgSettings
        s.selectAll().where { s.orgId eq orgId }.firstOrNull()?.let { row ->
            BODY.decodeFromString(CloudSettings.serializer(), row[s.body])
                .copy(updatedAt = row[s.updatedAt], updatedBy = row[s.updatedBy])
        }
    }

    override suspend fun save(orgId: Uuid, settings: CloudSettings, expectedUpdatedAt: Instant?): Boolean {
        val body = BODY.encodeToString(CloudSettings.serializer(), settings)
        val s = CloudOrgSettings
        return database.inOrg(orgId) {
            val changed = if (expectedUpdatedAt == null) {
                // The first save: one taken meanwhile is no row inserted.
                s.insertIgnore {
                    it[s.orgId] = orgId
                    it[s.body] = body
                    it[updatedAt] = settings.updatedAt
                    it[updatedBy] = settings.updatedBy
                }.insertedCount
            } else {
                s.update({ (s.orgId eq orgId) and (s.updatedAt eq expectedUpdatedAt) }) {
                    it[s.body] = body
                    it[updatedAt] = settings.updatedAt
                    it[updatedBy] = settings.updatedBy
                }
            }
            changed == 1
        }
    }
}

// Every field written: a value saved equal to today's default is not read back as a later, different default.
private val BODY = Json(OstromJson) { encodeDefaults = true }
