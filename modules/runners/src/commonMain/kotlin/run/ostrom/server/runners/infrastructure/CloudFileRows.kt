// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.runners.domain.CloudConversationException
import run.ostrom.server.runners.domain.CloudConversationRules
import run.ostrom.server.runners.domain.CloudFile
import run.ostrom.server.runners.domain.CloudFileRef
import run.ostrom.server.runners.domain.CloudFileRules
import run.ostrom.server.runners.domain.NewInput
import run.ostrom.server.runners.domain.toRef
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The file rows the conversation's stores read and write, in the caller's transaction, under the session row lock.

/** A file as stored. */
internal fun readFile(row: ResultRow): CloudFile {
    val f = RunnersSessionFiles
    return CloudFile(
        row[f.id], row[f.orgId], row[f.sessionId], row[f.authorId], row[f.credentialId], row[f.name],
        row[f.contentType], row[f.size], row[f.sha256], row[f.clientKey], row[f.requestDigest], row[f.inputId],
        row[f.createdAt], row[f.completedAt], row[f.expiresAt], row[f.consumedAt], row[f.deletingSince],
    )
}

/** The files each of [inputIds] carries, in one indexed read (never one per input). */
internal fun filesOf(orgId: Uuid, inputIds: Collection<Uuid>): Map<Uuid?, List<CloudFileRef>> {
    if (inputIds.isEmpty()) return emptyMap()
    val f = RunnersSessionFiles
    return f.select(f.columns)
        .where { (f.orgId eq orgId) and (f.inputId inList inputIds) and f.completedAt.isNotNull() }
        .orderBy(f.createdAt to SortOrder.ASC, f.id to SortOrder.ASC)
        .map(::readFile)
        .groupBy({ it.inputId }, CloudFile::toRef)
}

/**
 * The files [new] names, in upload order, now carried by its input: each complete, the author's, in the session, not
 * expired, not being deleted and carried by no other message (`409 file_unavailable`, the same answer whatever the
 * reason, so nobody learns of another member's files), together at most [NewInput.fileBytes] (`413
 * files_too_large`). Bound under the session lock, so a file is carried by one message only.
 */
@Suppress("ThrowsCount") // A malformed, an unavailable and a too large file are each their own answer.
internal fun bindFiles(new: NewInput, now: Instant): List<CloudFileRef> {
    if (new.fileIds.isEmpty()) return emptyList()
    val input = new.input
    val ids = new.fileIds.map { Uuid.parseOrNull(it) ?: throw unavailable() }
    val f = RunnersSessionFiles
    val rows = f.select(f.columns).where {
        (f.orgId eq input.orgId) and (f.id inList ids) and (f.sessionId eq input.sessionId) and
            (f.authorId eq input.authorId) and f.inputId.isNull() and f.deletingSince.isNull() and
            f.completedAt.isNotNull() and (f.expiresAt greater now)
    }.forUpdate().map(::readFile).associateBy { it.id }
    if (rows.size != ids.size) throw unavailable()
    if (rows.values.sumOf { it.size } > new.fileBytes) {
        throw CloudConversationException(
            ErrorKind.PAYLOAD_TOO_LARGE,
            "files_too_large",
            "A message's files are limited to ${new.fileBytes / CloudFileRules.MEBIBYTE} MiB together on this server.",
        )
    }
    f.update({ (f.orgId eq input.orgId) and (f.id inList ids) and f.inputId.isNull() }) { it[inputId] = input.id }
    // In upload order, as every later read lists them (TeamCode's).
    return rows.values.sortedWith(compareBy<CloudFile>({ it.createdAt }, { it.id.toString() })).map(CloudFile::toRef)
}

/** The files of a finished input are no longer needed: the sweep removes their bytes, then their rows. */
internal fun releaseFiles(orgId: Uuid, inputId: Uuid, consumed: Boolean, now: Instant) {
    val f = RunnersSessionFiles
    f.update({ (f.orgId eq orgId) and (f.inputId eq inputId) and f.deletingSince.isNull() }) {
        it[deletingSince] = now
        if (consumed) it[consumedAt] = now
    }
}

private fun unavailable() = CloudConversationRules.conflict(
    "file_unavailable",
    "A file is unknown, expired or already sent. Upload it again.",
)
