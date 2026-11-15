// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.governance.infrastructure

import run.ostrom.server.governance.domain.Confirmation
import run.ostrom.server.governance.domain.ConfirmationCredential
import run.ostrom.server.governance.domain.ConfirmationError
import run.ostrom.server.governance.domain.ConfirmationOutcome
import run.ostrom.server.governance.domain.ConfirmationPerson
import run.ostrom.server.governance.domain.ConfirmationStatus
import run.ostrom.server.governance.domain.ConfirmationsRepository
import run.ostrom.server.kernel.auth.GovernanceWrite
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.present
import run.ostrom.server.kernel.database.write
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.statements.buildStatement
import org.jetbrains.exposed.v1.jdbc.statements.toExecutable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ConfirmationsRepository] on PostgreSQL through Exposed ([GovernanceConfirmations], schema of
 * [GovernanceMigrations]): each query names its organization (`org_id = …`) and runs in [JdbcDatabase.inOrg], whose
 * row-level security is the second wall, except the sweep's read of which organizations have expired confirmations
 * (`governance_expired_organizations`, a function owned by `ostrom_router`, read as the serving role).
 *
 * @param database The JDBC pool.
 */
internal class ConfirmationsRepositoryImpl(private val database: JdbcDatabase) : ConfirmationsRepository {
    private val c = GovernanceConfirmations

    override suspend fun insert(confirmation: Confirmation) {
        database.inOrg(confirmation.orgId) { write { confirmationInsert(confirmation) } }
    }

    override suspend fun get(orgId: Uuid, id: String): Confirmation? =
        database.inOrg(orgId) { confirmationRead(orgId, id).singleOrNull()?.toConfirmation() }

    override suspend fun waiting(orgId: Uuid, userId: String, now: Instant): List<Confirmation> =
        database.inOrg(orgId) { waitingRead(orgId, userId, now).map { it.toConfirmation() } }

    override suspend fun expire(orgId: Uuid, now: Instant): List<Confirmation> = database.inOrg(orgId) {
        buildStatement { expireOverdue(orgId, now) }.toExecutable().execute(this).map { it.toConfirmation() }
    }

    override suspend fun answer(orgId: Uuid, id: String, outcome: ConfirmationOutcome): Boolean =
        database.inOrg(orgId) {
            write { answerUpdate(orgId, id, outcome) } == 1
        }

    override suspend fun settle(orgId: Uuid, id: String, result: JsonElement?, error: ConfirmationError?) {
        database.inOrg(orgId) {
            write {
                if (error == null) resultUpdate(orgId, id, result.encoded()) else errorUpdate(orgId, id, error)
            }
        }
    }

    override suspend fun organizationsWithExpired(now: Instant, limit: Int): List<Uuid> = database.query {
        val organization = expiredOrganizations(now, limit)
        expiredOrganizationsRead(now, limit).map { it[organization] }
    }

    private fun ResultRow.json(column: Column<String?>): JsonElement? {
        val text = this[column] ?: return null
        return Json.parseToJsonElement(text).takeUnless { it is JsonNull }
    }

    private fun ResultRow.toConfirmation(): Confirmation {
        val answeredBy = this[c.answeredByMember]?.let {
            ConfirmationPerson(present(c.answeredByUser, c.answeredByMember), it)
        }
        val error = this[c.errorType]?.let { ConfirmationError(it, present(c.errorMessage, c.errorType)) }
        val status = this[c.status]
        return Confirmation(
            id = this[c.id],
            orgId = this[c.orgId],
            write = GovernanceWrite(this[c.kind], Json.parseToJsonElement(this[c.payload]).jsonObject),
            scope = this[c.scope],
            method = this[c.method],
            path = this[c.path],
            summary = this[c.summary],
            before = Json.parseToJsonElement(this[c.beforeJson]).takeUnless { it is JsonNull },
            after = Json.parseToJsonElement(this[c.afterJson]).takeUnless { it is JsonNull },
            requestedBy = ConfirmationPerson(this[c.requestedByUser], this[c.requestedByMember]),
            credential = ConfirmationCredential.entries.first { it.wire == this[c.credential] },
            tokenId = this[c.tokenId],
            tokenName = this[c.tokenName],
            status = ConfirmationStatus.entries.first { it.wire == status },
            createdAt = this[c.createdAt],
            expiresAt = this[c.expiresAt],
            answeredBy = answeredBy,
            answeredAt = this[c.answeredAt],
            result = json(c.resultJson),
            error = error,
            // a settled write's result is written, `null` for none: SQL NULL is a confirmed write still being made
            settled = status != ConfirmationStatus.CONFIRMED.wire || this[c.resultJson] != null,
        )
    }
}
