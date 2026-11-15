// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.infrastructure

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeRepository
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
 * [CloudRuntimeRepository] on PostgreSQL through Exposed: one row of `cloud_runtime`, its fields as JSON.
 *
 * @param database The serving role's pool (the instance's row, no organization).
 */
class CloudRuntimeRepositoryImpl(private val database: JdbcDatabase) : CloudRuntimeRepository {
    override suspend fun get(): CloudRuntime? = database.query {
        val r = CloudRuntimeRows
        r.selectAll().where { r.id eq RUNTIME_ROW }.firstOrNull()?.let { row ->
            BODY.decodeFromString(CloudRuntime.serializer(), row[r.body]).copy(updatedAt = row[r.updatedAt])
        }
    }

    override suspend fun save(runtime: CloudRuntime, expectedUpdatedAt: Instant?, by: Uuid?): Boolean {
        val body = BODY.encodeToString(CloudRuntime.serializer(), runtime.copy(updatedAt = null))
        val at = runtime.updatedAt
        val r = CloudRuntimeRows
        return database.query {
            if (expectedUpdatedAt == null) {
                // The first save: one taken meanwhile is no row inserted.
                r.insertIgnore {
                    it[id] = RUNTIME_ROW
                    it[r.body] = body
                    it[updatedAt] = at
                    it[updatedBy] = by
                }.insertedCount == 1
            } else {
                r.update({ (r.id eq RUNTIME_ROW) and (r.updatedAt eq expectedUpdatedAt) }) {
                    it[r.body] = body
                    it[updatedAt] = at
                    it[updatedBy] = by
                } == 1
            }
        }
    }
}

private const val RUNTIME_ROW: Short = 1

// Every field written, nulls included: a retention an administrator turned off (null) is not read back as the default.
private val BODY = Json(OstromJson) {
    encodeDefaults = true
    explicitNulls = true
}
