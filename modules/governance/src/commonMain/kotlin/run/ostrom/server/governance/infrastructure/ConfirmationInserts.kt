// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.governance.infrastructure

import run.ostrom.server.governance.domain.Confirmation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.jdbc.insert

private val C = GovernanceConfirmations

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
