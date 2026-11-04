// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance.infrastructure

import dev.ostrom.server.governance.domain.Confirmation
import dev.ostrom.server.governance.domain.ConfirmationCredential
import dev.ostrom.server.governance.domain.ConfirmationError
import dev.ostrom.server.governance.domain.ConfirmationOutcome
import dev.ostrom.server.governance.domain.ConfirmationPerson
import dev.ostrom.server.governance.domain.ConfirmationStatus
import dev.ostrom.server.governance.domain.ConfirmationsRepository
import dev.ostrom.server.kernel.auth.GovernanceWrite
import dev.ostrom.server.kernel.database.withOrganization
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ConfirmationsRepository] on PostgreSQL: each query runs within its organization (`withOrganization`, row level
 * security forced), except the sweep's read of which organizations have expired confirmations
 * (`governance_expired_organizations`, owned by `ostrom_router`).
 *
 * @param driver The serving role's pool.
 */
class PostgresConfirmationsRepository(private val driver: Driver) : ConfirmationsRepository {
    override suspend fun insert(confirmation: Confirmation) {
        driver.withOrganization(confirmation.orgId) { tx ->
            tx.execute(
                Statement.create(INSERT)
                    .bind("org_id", confirmation.orgId)
                    .bind("id", confirmation.id)
                    .bind("kind", confirmation.write.kind)
                    .bind("scope", confirmation.scope)
                    .bind("method", confirmation.method)
                    .bind("path", confirmation.path)
                    .bind("payload", Json.encodeToString(JsonElement.serializer(), confirmation.write.payload))
                    .bind("summary", confirmation.summary)
                    .bind("before_json", confirmation.before.encoded())
                    .bind("after_json", confirmation.after.encoded())
                    .bind("requested_by_user", confirmation.requestedBy.userId)
                    .bind("requested_by_member", confirmation.requestedBy.memberId)
                    .bind("credential", confirmation.credential.wire)
                    .nullable("token_id", confirmation.tokenId)
                    .nullable("token_name", confirmation.tokenName)
                    .bind("status", confirmation.status.wire)
                    .bind("created_at", confirmation.createdAt)
                    .bind("expires_at", confirmation.expiresAt),
            ).getOrThrow()
        }
    }

    override suspend fun get(orgId: Uuid, id: String): Confirmation? = driver.withOrganization(orgId) { tx ->
        tx.fetchAll(Statement.create(GET).bind("org_id", orgId).bind("id", id)).getOrThrow()
            .firstOrNull()?.toConfirmation()
    }

    override suspend fun waiting(orgId: Uuid, userId: String, now: Instant): List<Confirmation> =
        driver.withOrganization(orgId) { tx ->
            tx.fetchAll(
                Statement.create(WAITING).bind("org_id", orgId).bind("user", userId).bind("now", now),
            ).getOrThrow().map { it.toConfirmation() }
        }

    override suspend fun expire(orgId: Uuid, now: Instant): List<Confirmation> = driver.withOrganization(orgId) { tx ->
        tx.fetchAll(Statement.create(EXPIRE).bind("org_id", orgId).bind("now", now)).getOrThrow()
            .map { it.toConfirmation() }
    }

    override suspend fun answer(orgId: Uuid, id: String, outcome: ConfirmationOutcome): Boolean =
        driver.withOrganization(orgId) { tx ->
            tx.execute(
                Statement.create(ANSWER)
                    .bind("org_id", orgId)
                    .bind("id", id)
                    .bind("status", outcome.status.wire)
                    .bind("by_user", outcome.by.userId)
                    .bind("by_member", outcome.by.memberId)
                    .bind("at", outcome.at)
                    .nullable("error_type", outcome.error?.type)
                    .nullable("error_message", outcome.error?.message),
            ).getOrThrow() == 1L
        }

    override suspend fun settle(orgId: Uuid, id: String, result: JsonElement?, error: ConfirmationError?) {
        driver.withOrganization(orgId) { tx ->
            val statement = if (error == null) {
                Statement.create(SETTLE_RESULT).bind("result", result.encoded())
            } else {
                Statement.create(SETTLE_ERROR).bind("error_type", error.type).bind("error_message", error.message)
            }
            tx.execute(statement.bind("org_id", orgId).bind("id", id)).getOrThrow()
        }
    }

    override suspend fun organizationsWithExpired(now: Instant, limit: Int): List<Uuid> = driver.fetchAll(
        Statement.create(EXPIRED_ORGANIZATIONS).bind("now", now).bind("limit", limit),
    ).getOrThrow().map { it.get("org_id").asUuid() }

    private fun ResultSet.Row.json(column: String): JsonElement? {
        val text = get(column).asStringOrNull() ?: return null
        return Json.parseToJsonElement(text).takeUnless { it is JsonNull }
    }

    private fun ResultSet.Row.toConfirmation(): Confirmation {
        val answeredBy = get("answered_by_member").asUuidOrNull()?.let { member ->
            ConfirmationPerson(get("answered_by_user").asString(), member)
        }
        val error = get("error_type").asStringOrNull()?.let { ConfirmationError(it, get("error_message").asString()) }
        return Confirmation(
            id = get("id").asString(),
            orgId = get("org_id").asUuid(),
            write = GovernanceWrite(
                get("kind").asString(),
                Json.parseToJsonElement(get("payload").asString()).jsonObject,
            ),
            scope = get("scope").asString(),
            method = get("method").asString(),
            path = get("path").asString(),
            summary = get("summary").asString(),
            before = json("before_json"),
            after = json("after_json"),
            requestedBy = ConfirmationPerson(get("requested_by_user").asString(), get("requested_by_member").asUuid()),
            credential = ConfirmationCredential.entries.first { it.wire == get("credential").asString() },
            tokenId = get("token_id").asStringOrNull(),
            tokenName = get("token_name").asStringOrNull(),
            status = ConfirmationStatus.entries.first { it.wire == get("status").asString() },
            createdAt = get("created_at").asInstant(),
            expiresAt = get("expires_at").asInstant(),
            answeredBy = answeredBy,
            answeredAt = get("answered_at").asInstantOrNull(),
            result = json("result_json"),
            error = error,
        )
    }

    /** The SQL of the repository, also read by the query plans test. */
    companion object {
        private val COLUMN_NAMES = listOf(
            "org_id", "id", "kind", "scope", "method", "path", "payload", "summary", "before_json", "after_json",
            "requested_by_user", "requested_by_member", "credential", "token_id", "status", "created_at", "expires_at",
            "token_name",
        )
        private val READ_COLUMNS = (
            COLUMN_NAMES + listOf(
                "answered_by_user", "answered_by_member", "answered_at", "result_json", "error_type", "error_message",
            )
            ).joinToString(", ")

        private val INSERT = "INSERT INTO governance_confirmations (${COLUMN_NAMES.joinToString(", ")}) " +
            "VALUES (${COLUMN_NAMES.joinToString(", ") { ":$it" }})"

        /** One confirmation, by its key. */
        val GET = "SELECT $READ_COLUMNS FROM governance_confirmations WHERE org_id = :org_id AND id = :id"

        /** A person's pending confirmations (index `governance_confirmations_waiting`). */
        val WAITING = "SELECT $READ_COLUMNS FROM governance_confirmations WHERE org_id = :org_id " +
            "AND requested_by_user = :user AND status = 'pending' AND expires_at > :now ORDER BY created_at"

        /** Expires the overdue ones of an organization, once each (index `governance_confirmations_expiry`). */
        val EXPIRE = "UPDATE governance_confirmations SET status = 'expired' WHERE org_id = :org_id " +
            "AND status = 'pending' AND expires_at <= :now RETURNING $READ_COLUMNS"

        /** Answers a pending, unexpired confirmation: of two answers, the second changes nothing. */
        const val ANSWER: String =
            "UPDATE governance_confirmations SET status = :status, answered_by_user = :by_user, " +
            "answered_by_member = :by_member, answered_at = :at, error_type = :error_type, " +
            "error_message = :error_message WHERE org_id = :org_id AND id = :id AND status = 'pending' " +
            "AND expires_at > :at"

        /** Keeps what a confirmed write returned, by its key. */
        const val SETTLE_RESULT: String =
            "UPDATE governance_confirmations SET result_json = :result WHERE org_id = :org_id AND id = :id"

        /** Marks a confirmed write that failed, by its key. */
        const val SETTLE_ERROR: String = "UPDATE governance_confirmations SET status = 'failed', " +
            "error_type = :error_type, error_message = :error_message WHERE org_id = :org_id AND id = :id"

        /** The sweep's read across organizations. */
        const val EXPIRED_ORGANIZATIONS: String =
            "SELECT org_id FROM governance_expired_organizations(CAST(:now AS TIMESTAMPTZ), " +
            "CAST(:limit AS INTEGER)) AS o (org_id)"
    }
}

private fun Statement.nullable(name: String, value: String?): Statement =
    if (value == null) bindNull(name, String::class) else bind(name, value)

private fun JsonElement?.encoded(): String = Json.encodeToString(JsonElement.serializer(), this ?: JsonNull)
