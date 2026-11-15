// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.receipts.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

// The table of [ReceiptsMigrations], as Exposed reads and writes it: the migrations create the schema, this object
// only names its columns. An organization's, under row-level security, append-only.

/** The signed receipts, each stored as the exact JSON that was signed, under its id in its organization. */
internal object ReceiptsReceipts : Table("receipts_receipts") {
    val orgId = uuid("org_id")
    val id = text("id")
    val subjectKind = text("subject_kind")
    val subjectId = text("subject_id")
    val body = text("body")
    val createdAt = instant("created_at")
    val sessionId = text("session_id").nullable()
    val member = text("member").nullable()
    override val primaryKey = PrimaryKey(orgId, id)
}
