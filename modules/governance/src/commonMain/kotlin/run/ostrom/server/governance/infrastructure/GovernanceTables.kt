// SPDX-License-Identifier: Apache-2.0
@file:Suppress("MatchingDeclarationName") // One table: the file is the module's tables, as in every module.

package run.ostrom.server.governance.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/**
 * The table of [GovernanceMigrations], as Exposed reads and writes it. The migrations create the schema; this object
 * only names its columns and their types, and never creates anything (no `SchemaUtils`). It holds an organization's
 * rows under row-level security, read and written in `JdbcDatabase.inOrg` (migration 4 grants it to the tenant role),
 * each query filtering on the organization too. The JSON columns are text, written and read by the repository's own
 * serializer.
 */
internal object GovernanceConfirmations : Table("governance_confirmations") {
    val orgId = uuid("org_id")
    val id = text("id")
    val kind = text("kind")
    val scope = text("scope")
    val method = text("method")
    val path = text("path")
    val payload = text("payload")
    val summary = text("summary")
    val beforeJson = text("before_json")
    val afterJson = text("after_json")
    val requestedByUser = text("requested_by_user")
    val requestedByMember = uuid("requested_by_member")
    val credential = text("credential")
    val tokenId = text("token_id").nullable()
    val status = text("status")
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")
    val answeredByUser = text("answered_by_user").nullable()
    val answeredByMember = uuid("answered_by_member").nullable()
    val answeredAt = instant("answered_at").nullable()
    val resultJson = text("result_json").nullable()
    val errorType = text("error_type").nullable()
    val errorMessage = text("error_message").nullable()
    val tokenName = text("token_name").nullable()

    override val primaryKey = PrimaryKey(orgId, id)
}
