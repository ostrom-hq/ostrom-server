// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.governance.infrastructure

import run.ostrom.server.governance.domain.ConfirmationError
import run.ostrom.server.governance.domain.ConfirmationOutcome
import run.ostrom.server.governance.domain.ConfirmationRules
import run.ostrom.server.governance.domain.Confirmation
import run.ostrom.server.kernel.database.InstantColumnType
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.ReturningStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The statements of the confirmations (ConfirmationsRepositoryImpl), one function each so that
// GovernancePostgresTest explains exactly what the repository sends. Every one filters on the organization; the
// transaction's row-level security is the second wall.

private val C = GovernanceConfirmations

/** The wire value of a pending confirmation. */
private const val PENDING = "pending"

/** The wire value of a confirmed one. */
private const val CONFIRMED = "confirmed"

/** Stores a new confirmation. */
internal fun StatementBuilder.confirmationInsert(confirmation: Confirmation): InsertStatement<Number> =
    C.insert {
        it[orgId] = confirmation.orgId
        it[id] = confirmation.id
        it[kind] = confirmation.write.kind
        it[scope] = confirmation.scope
        it[method] = confirmation.method
        it[path] = confirmation.path
        it[payload] = Json.encodeToString(JsonElement.serializer(), confirmation.write.payload)
        it[summary] = confirmation.summary
        it[beforeJson] = confirmation.before.encoded()
        it[afterJson] = confirmation.after.encoded()
        it[requestedByUser] = confirmation.requestedBy.userId
        it[requestedByMember] = confirmation.requestedBy.memberId
        it[credential] = confirmation.credential.wire
        it[tokenId] = confirmation.tokenId
        it[tokenName] = confirmation.tokenName
        it[status] = confirmation.status.wire
        it[createdAt] = confirmation.createdAt
        it[expiresAt] = confirmation.expiresAt
    }

/** A JSON column's text: `null` for none. */
internal fun JsonElement?.encoded(): String = Json.encodeToString(JsonElement.serializer(), this ?: JsonNull)

/** One confirmation, by its key. */
internal fun confirmationRead(orgId: Uuid, id: String): Query =
    C.select(C.columns).where { (C.orgId eq orgId) and (C.id eq id) }

/**
 * A person's pending confirmations, oldest first (index `governance_confirmations_waiting`). At most
 * [ConfirmationRules.MAX_WAITING]: that is how many a person may have waiting, so no more are ever read.
 */
internal fun waitingRead(orgId: Uuid, userId: String, now: Instant): Query = C.select(C.columns)
    .where {
        (C.orgId eq orgId) and (C.requestedByUser eq userId) and (C.status eq PENDING) and (C.expiresAt greater now)
    }
    .orderBy(C.createdAt to SortOrder.ASC)
    .limit(ConfirmationRules.MAX_WAITING)

/**
 * Expires the overdue ones of an organization, and fails the confirmed ones whose result was lost, once each (indexes
 * `governance_confirmations_expiry`, `governance_confirmations_unsettled`). The rows it marks are the backlog of one
 * sweep pass, one a minute: a handful, bounded by the partial indexes.
 */
internal fun StatementBuilder.expireOverdue(orgId: Uuid, now: Instant): ReturningStatement {
    val pending = C.status eq PENDING
    val overdue = (pending and (C.expiresAt lessEq now)) or
        ((C.status eq CONFIRMED) and C.resultJson.isNull() and
            (C.expiresAt lessEq now - ConfirmationRules.RESULT_GRACE))
    return C.updateReturning(C.columns, where = { (C.orgId eq orgId) and overdue }) {
        it[status] = case().When(pending, stringParam("expired")).Else(stringParam("failed"))
        it[errorType] = case().When(pending, errorType).Else(nullableText(ConfirmationRules.RESULT_LOST))
        it[errorMessage] = case().When(pending, errorMessage).Else(nullableText(ConfirmationRules.RESULT_LOST_MESSAGE))
    }
}

/** Answers a pending, unexpired confirmation: of two answers, the second changes nothing. */
internal fun StatementBuilder.answerUpdate(orgId: Uuid, id: String, outcome: ConfirmationOutcome): UpdateStatement =
    C.update({ (C.orgId eq orgId) and (C.id eq id) and (C.status eq PENDING) and (C.expiresAt greater outcome.at) }) {
        it[status] = outcome.status.wire
        it[answeredByUser] = outcome.by.userId
        it[answeredByMember] = outcome.by.memberId
        it[answeredAt] = outcome.at
        it[errorType] = outcome.error?.type
        it[errorMessage] = outcome.error?.message
    }

/** Keeps what a confirmed write returned, by its key; never on one the sweep already failed (result lost). */
internal fun StatementBuilder.resultUpdate(orgId: Uuid, id: String, result: String): UpdateStatement =
    C.update({ (C.orgId eq orgId) and (C.id eq id) and (C.status eq CONFIRMED) }) {
        it[resultJson] = result
    }

/** Marks a confirmed write that failed, by its key; never one the sweep already failed (result lost). */
internal fun StatementBuilder.errorUpdate(orgId: Uuid, id: String, error: ConfirmationError): UpdateStatement =
    C.update({ (C.orgId eq orgId) and (C.id eq id) and (C.status eq CONFIRMED) }) {
        it[status] = "failed"
        it[errorType] = error.type
        it[errorMessage] = error.message
    }

/** A bound text as an expression of a nullable column's type, for a `CASE` that keeps the column otherwise. */
private fun nullableText(value: String): Expression<String?> = QueryParameter(value, TextColumnType())

/** The function the sweep reads across organizations (owned by `ostrom_router`, migration 3). */
internal fun expiredOrganizations(now: Instant, limit: Int): CustomFunction<Uuid> = CustomFunction(
    "governance_expired_organizations",
    UuidColumnType(),
    QueryParameter(now, InstantColumnType()),
    QueryParameter(limit, IntegerColumnType()),
)

/** The sweep's read across organizations: at most [limit] of them, through the function. */
internal fun expiredOrganizationsRead(now: Instant, limit: Int): Query =
    Table.Dual.select(expiredOrganizations(now, limit))
