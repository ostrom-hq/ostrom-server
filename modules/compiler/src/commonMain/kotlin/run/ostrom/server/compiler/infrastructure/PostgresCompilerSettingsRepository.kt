// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.infrastructure

import run.ostrom.server.compiler.domain.models.CompilerSettings
import run.ostrom.server.compiler.domain.models.StoredCompilerSettings
import run.ostrom.server.compiler.domain.repositories.CompilerSettingsRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [CompilerSettingsRepository] on PostgreSQL (sqlx4k): one row per organization that saved its settings, under its
 * row level security, read through the primary key.
 *
 * @param driver The connection pool.
 */
class PostgresCompilerSettingsRepository(private val driver: Driver) : CompilerSettingsRepository {
    override suspend fun read(orgId: Uuid): StoredCompilerSettings? = driver.inOrg(orgId) {
        fetch(Statement.create("SELECT $COLUMNS FROM compiler_settings WHERE org_id = :org").bind("org", orgId))
            .firstOrNull()?.toStored()
    }

    override suspend fun save(
        orgId: Uuid,
        settings: CompilerSettings,
        by: String,
        at: Instant,
        expected: Instant?,
    ): StoredCompilerSettings? = driver.inOrg(orgId) {
        // A first save never replaces one that came before it; a later one replaces only the version it read.
        val statement = if (expected == null) {
            Statement.create(
                "INSERT INTO compiler_settings (org_id, pin, code_publishers, prod_approver, updated_by, updated_at) " +
                    "VALUES (:org, :pin, :publishers, :approver, :by, :at) ON CONFLICT (org_id) DO NOTHING " +
                    "RETURNING $COLUMNS",
            )
        } else {
            Statement.create(
                "UPDATE compiler_settings SET pin = :pin, code_publishers = :publishers, prod_approver = :approver, " +
                    "updated_by = :by, updated_at = :at WHERE org_id = :org AND updated_at = :expected " +
                    "RETURNING $COLUMNS",
            ).bind("expected", expected)
        }
        val publishers = settings.codePublishers?.let { Json.encodeToString(LOGINS, it) }
        statement.bind("org", orgId).bind("pin", if (settings.manualPin) MANUAL else AUTO)
            .bind("publishers", publishers).bind("approver", settings.prodApprover)
            .bind("by", by).bind("at", at)
        fetch(statement).firstOrNull()?.toStored()
    }

    private fun ResultSet.Row.toStored() = StoredCompilerSettings(
        CompilerSettings(
            manualPin = get("pin").asString() == MANUAL,
            codePublishers = get("code_publishers").asStringOrNull()?.let { Json.decodeFromString(LOGINS, it) },
            prodApprover = get("prod_approver").asStringOrNull(),
        ),
        updatedBy = get("updated_by").asString(),
        updatedAt = get("updated_at").asInstant(),
    )
}

// Top-level, not in a private companion: a companion property has a getter nothing calls, which Kover counts.
private val LOGINS = ListSerializer(String.serializer())
private const val COLUMNS = "pin, code_publishers, prod_approver, updated_by, updated_at"
private const val MANUAL = "manual"
private const val AUTO = "auto"
