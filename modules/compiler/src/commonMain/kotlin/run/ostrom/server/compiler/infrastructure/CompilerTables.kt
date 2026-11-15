// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

// The tables of [CompilerMigrations], as Exposed reads and writes them. The migrations create the schema; these objects
// only name its columns and their types.

/** The organization's workflows, with their version for optimistic saves (version 1). */
internal object CompilerWorkflows : Table("compiler_workflows") {
    val orgId = uuid("org_id")
    val id = text("id")
    val doc = text("doc")
    val version = integer("version")
    val updatedBy = text("updated_by")
    val updatedAt = instant("updated_at")
    val createdAt = instant("created_at")

    override val primaryKey = PrimaryKey(orgId, id)
}

/** The decisions taken on the workflows (version 1). */
internal object CompilerDecisions : Table("compiler_decisions") {
    val orgId = uuid("org_id")
    val id = text("id")
    val subjectKind = text("subject_kind")
    val subjectId = text("subject_id")
    val decidedBy = text("decided_by")
    val decidedAt = text("decided_at")
    val receiptId = text("receipt_id").nullable()

    override val primaryKey = PrimaryKey(orgId, id)
}

/** The harness versions, by repository key and digest (version 1). */
internal object CompilerHarnesses : Table("compiler_harnesses") {
    val orgId = uuid("org_id")
    val repoKey = text("repo_key")
    val digest = text("digest")
    val version = text("version")
    val receiptId = text("receipt_id").nullable()

    override val primaryKey = PrimaryKey(orgId, repoKey, digest)
}

/** The signed packages (version 1). */
internal object CompilerPackages : Table("compiler_packages") {
    val orgId = uuid("org_id")
    val contentDigest = text("content_digest")
    val digest = text("digest")
    val version = text("version")
    val repo = text("repo").nullable()
    val compiledAt = text("compiled_at")
    val envelope = text("envelope")
    val counts = text("counts")
    val revoked = bool("revoked")
    val revokedBy = text("revoked_by").nullable()
    val revokeReason = text("revoke_reason").nullable()

    override val primaryKey = PrimaryKey(orgId, contentDigest)
}

/** The pinned package of each repository key (version 1). */
internal object CompilerPins : Table("compiler_pins") {
    val orgId = uuid("org_id")
    val repoKey = text("repo_key")
    val contentDigest = text("content_digest")
    val pinnedBy = text("pinned_by")
    val pinnedAt = instant("pinned_at")

    override val primaryKey = PrimaryKey(orgId, repoKey)
}

/** The packages audit, in the order of its `seq`, which the database numbers (version 1). */
internal object CompilerAudit : Table("compiler_audit") {
    val seq = long("seq").databaseGenerated()
    val orgId = uuid("org_id")
    val id = text("id")
    val at = text("at")
    val event = text("event")
    val actor = text("actor")
    val subject = text("subject")
    val meta = text("meta")

    override val primaryKey = PrimaryKey(orgId, id)
}

/** Who may decide the workflows, one row per organization that changed it (version 3). */
internal object CompilerWorkflowSettings : Table("compiler_workflow_settings") {
    val orgId = uuid("org_id")
    val mode = text("mode")
    val memberIds = text("member_ids")
    val updatedBy = text("updated_by")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(orgId)
}

/** How the organization compiles, one row per organization that saved it (version 4). */
internal object CompilerSettingsRows : Table("compiler_settings") {
    val orgId = uuid("org_id")
    val pin = text("pin")
    val codePublishers = text("code_publishers").nullable()
    val prodApprover = text("prod_approver").nullable()
    val updatedBy = text("updated_by")
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(orgId)
}
