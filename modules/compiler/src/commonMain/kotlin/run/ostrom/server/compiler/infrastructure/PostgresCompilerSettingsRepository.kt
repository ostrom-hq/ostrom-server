// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.infrastructure

import run.ostrom.server.compiler.domain.models.CompilerSettings
import run.ostrom.server.compiler.domain.models.StoredCompilerSettings
import run.ostrom.server.compiler.domain.repositories.CompilerSettingsRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insertReturning
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.updateReturning
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [CompilerSettingsRepository] on PostgreSQL through Exposed: one row per organization that saved its settings, under
 * its row level security (`withOrganization`), read through the primary key.
 *
 * @param database The serving role's pool.
 */
class PostgresCompilerSettingsRepository(private val database: JdbcDatabase) : CompilerSettingsRepository {
    override suspend fun read(orgId: Uuid): StoredCompilerSettings? = database.withOrganization(orgId) {
        val s = CompilerSettingsRows
        val row = s.select(COLUMNS).where { s.orgId eq orgId }.firstOrNull()
        if (row == null) null else row.toStored()
    }

    override suspend fun save(
        orgId: Uuid,
        settings: CompilerSettings,
        by: String,
        at: Instant,
        expected: Instant?,
    ): StoredCompilerSettings? = database.withOrganization(orgId) {
        val s = CompilerSettingsRows
        // A first save never replaces one that came before it; a later one replaces only the version it read.
        val saved = if (expected == null) {
            s.insertReturning(COLUMNS, ignoreErrors = true) {
                it[s.orgId] = orgId
                it.fields(settings, by, at)
            }
        } else {
            s.updateReturning(COLUMNS, where = { (s.orgId eq orgId) and (s.updatedAt eq expected) }) {
                it.fields(settings, by, at)
            }
        }
        val row = saved.firstOrNull()
        if (row == null) null else row.toStored()
    }
}

private fun UpdateBuilder<*>.fields(settings: CompilerSettings, by: String, at: Instant) {
    val s = CompilerSettingsRows
    val publishers = settings.codePublishers
    this[s.pin] = if (settings.manualPin) MANUAL else AUTO
    this[s.codePublishers] = if (publishers == null) null else Json.encodeToString(LOGINS, publishers)
    this[s.prodApprover] = settings.prodApprover
    this[s.updatedBy] = by
    this[s.updatedAt] = at
}

private fun ResultRow.toStored(): StoredCompilerSettings {
    val s = CompilerSettingsRows
    val publishers = this[s.codePublishers]
    return StoredCompilerSettings(
        CompilerSettings(
            manualPin = this[s.pin] == MANUAL,
            codePublishers = if (publishers == null) null else Json.decodeFromString(LOGINS, publishers),
            prodApprover = this[s.prodApprover],
        ),
        updatedBy = this[s.updatedBy],
        updatedAt = this[s.updatedAt],
    )
}

private val COLUMNS = CompilerSettingsRows.run { listOf(pin, codePublishers, prodApprover, updatedBy, updatedAt) }

// Top-level, not in a private companion: a companion property has a getter nothing calls, which Kover counts.
private val LOGINS = ListSerializer(String.serializer())
private const val MANUAL = "manual"
private const val AUTO = "auto"
