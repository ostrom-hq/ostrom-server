// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/**
 * Files sent to sessions (TeamCode's `CloudSessionFiles`, [RunnersMigrations]' `session_files`): metadata only, the
 * bytes are in the organization's bucket under `session-files/`. [contentType], [sha256] and [completedAt] are null
 * while the upload runs; [inputId] is the message that carries the file; [deletingSince] marks a file whose bytes the
 * sweep removes (acknowledged, expired or abandoned) before the row.
 */
internal object RunnersSessionFiles : Table("runners_session_files") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val sessionId = uuid("session_id")
    val authorId = text("author_id")
    val credentialId = text("credential_id").nullable()
    val name = text("name")
    val contentType = text("content_type").nullable()
    val size = long("size")
    val sha256 = text("sha256").nullable()

    /** The `Idempotency-Key` of the upload, unique per session and author. */
    val clientKey = text("client_key").nullable()
    val requestDigest = text("request_digest").nullable()
    val inputId = uuid("input_id").nullable()
    val createdAt = instant("created_at")
    val completedAt = instant("completed_at").nullable()
    val expiresAt = instant("expires_at")
    val consumedAt = instant("consumed_at").nullable()
    val deletingSince = instant("deleting_since").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** What each member uploaded per UTC day ([day]: days since the epoch), for the daily limit. */
internal object RunnersFileUsage : Table("runners_file_usage") {
    val orgId = uuid("org_id")
    val memberId = text("member_id")
    val day = long("day")
    val bytes = long("bytes")
    val files = integer("files")

    override val primaryKey = PrimaryKey(orgId, memberId, day)
}
