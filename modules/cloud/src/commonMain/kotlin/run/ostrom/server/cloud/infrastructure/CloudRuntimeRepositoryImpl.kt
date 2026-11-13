// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.infrastructure

import run.ostrom.server.cloud.domain.CloudRuntime
import run.ostrom.server.cloud.domain.CloudRuntimeRepository
import run.ostrom.server.kernel.http.OstromJson
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** [CloudRuntimeRepository] on PostgreSQL: one row of `cloud_runtime`, its fields as JSON. */
class CloudRuntimeRepositoryImpl(private val driver: Driver) : CloudRuntimeRepository {
    override suspend fun get(): CloudRuntime? = driver
        .fetchAll("SELECT body, updated_at FROM cloud_runtime WHERE id = 1")
        .getOrThrow().firstOrNull()?.let { row ->
            BODY.decodeFromString(CloudRuntime.serializer(), row.get("body").asString())
                .copy(updatedAt = row.get("updated_at").asInstant())
        }

    override suspend fun save(runtime: CloudRuntime, expectedUpdatedAt: Instant?, by: Uuid?): Boolean {
        val sql = if (expectedUpdatedAt == null) {
            "INSERT INTO cloud_runtime (id, body, updated_at, updated_by) VALUES (1, :body, :at, :by) " +
                "ON CONFLICT (id) DO NOTHING"
        } else {
            "UPDATE cloud_runtime SET body = :body, updated_at = :at, updated_by = :by " +
                "WHERE id = 1 AND updated_at = :expected"
        }
        val statement = Statement.create(sql)
            .bind("body", BODY.encodeToString(CloudRuntime.serializer(), runtime.copy(updatedAt = null)))
            .bind("at", runtime.updatedAt)
            .bind("by", by)
            .let { if (expectedUpdatedAt == null) it else it.bind("expected", expectedUpdatedAt) }
        return driver.execute(statement).getOrThrow() == 1L
    }
}

// Every field written, nulls included: a retention an administrator turned off (null) is not read back as the default.
private val BODY = Json(OstromJson) {
    encodeDefaults = true
    explicitNulls = true
}
