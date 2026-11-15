// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.runners.domain.CloudConversationException
import run.ostrom.server.runners.domain.CloudConversationRules
import run.ostrom.server.runners.domain.CloudFile
import run.ostrom.server.runners.domain.CloudFileOutcome
import run.ostrom.server.runners.domain.CloudFilePolicy
import run.ostrom.server.runners.domain.CloudFileQuotaException
import run.ostrom.server.runners.domain.CloudFileRef
import run.ostrom.server.runners.domain.CloudFileService
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.toRef
import run.ostrom.server.kernel.errors.ErrorKind
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlin.uuid.Uuid

/**
 * The members' file routes (`runners.cloud.files.v1`, TeamCode's `/cloud/files/policy` and
 * `/cloud/sessions/{id}/files`): the organization's policy, and the upload of one file's raw bytes, authorized
 * exactly like a message. The body is read from the request's own channel in parts of 1 MiB, never buffered whole:
 * its bound is the runtime's file limit, checked against `Content-Length` before any byte is read and while reading
 * (`413 file_too_large`).
 *
 * @param files The files.
 * @param orgResolver The member a request acts for.
 */
@Suppress("ThrowsCount") // The session's id, the name sent twice, and the quota's header on its way out.
internal fun Route.fileRoutes(files: CloudFileService, orgResolver: PrincipalOrgResolver) {
    get<CloudFilePolicyApi> {
        val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_READ)
        call.respond(files.policy(caller).toDto())
    }
    post<CloudFilesApi> { params ->
        val principal = call.requirePrincipal()
        val caller = orgResolver.callerOf(principal, CLOUD_WRITE)
        val id = Uuid.parseOrNull(params.parent.id) ?: throw CloudSessionNotFoundException()
        val key = call.clientKey(null)
        if ((call.request.queryParameters.getAll("name")?.size ?: 0) > 1) {
            throw CloudConversationRules.invalid("invalid_file_name", "Send the name once.")
        }
        val announced = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        // The engine's own channel: the body limit's buffering is for JSON bodies, a file is streamed in parts.
        val channel = call.request.receiveChannel()
        val outcome = try {
            files.upload(caller, principal, id, params.name, announced, key) { max -> channel.readPart(max) }
        } catch (refusal: CloudFileQuotaException) {
            refusal.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
            throw refusal
        }
        when (outcome) {
            is CloudFileOutcome.Created -> call.respond(HttpStatusCode.Created, outcome.file.toDto())
            is CloudFileOutcome.Replayed -> {
                call.response.header(REPLAYED, "true")
                call.respond(HttpStatusCode.OK, outcome.file.toDto())
            }
        }
    }
}

/**
 * `GET /api/v1/cloud/runner/files/{id}`: the credential first, then the execution's fence, then the file a message
 * delivered to that very execution carries (`404` for any other), streamed part by part with its `X-Ostrom-Sha256`.
 */
internal fun Route.runnerFileRoute(dependencies: RunnerConversationRoutesDependencies, files: CloudFileService) {
    get<RunnerFileApi> { params ->
        val token = call.runnerToken()
        dependencies.authenticate.resolve(token)
        val execution = dependencies.execution(token, params.sessionId, params.epoch)
        val fileId = Uuid.parseOrNull(params.id) ?: throw notFound()
        val download = files.runnerFile(execution, fileId)
        call.response.header(HttpHeaders.ContentDisposition, "attachment")
        call.response.header(SHA256_HEADER, download.file.toRef().sha256)
        call.respondBytesWriter(ContentType.Application.OctetStream, HttpStatusCode.OK, download.file.size) {
            download.parts { writeFully(it) }
        }
    }
}

/** A file as the contract names it. */
internal fun CloudFile.toDto() = CloudFileDto(
    id.toString(), sessionId.toString(), name, size, contentType.orEmpty(), sha256.orEmpty(), createdAt.toString(),
    expiresAt.toString(),
)

internal fun CloudFilePolicy.toDto() =
    CloudFilePolicyDto(available, reason, maxFileBytes, maxMessageBytes, maxFilesPerMessage, types)

internal fun CloudFileRef.toDto() = RunnerFileDto(id.toString(), name, size, contentType, sha256)

/** Up to [max] bytes of the body, fewer only at its end; null once it is exhausted. */
internal suspend fun ByteReadChannel.readPart(max: Int): ByteArray? {
    val buffer = ByteArray(max)
    var filled = 0
    while (filled < max) {
        val count = readAvailable(buffer, filled, max - filled)
        if (count < 0) break
        filled += count
    }
    return when (filled) {
        0 -> null
        max -> buffer
        else -> buffer.copyOf(filled)
    }
}

private fun notFound() = CloudConversationException(ErrorKind.NOT_FOUND, "not_found", "File not found.")

private const val REPLAYED = "Idempotency-Replayed"

/** The SHA-256 of a download's bytes, in hexadecimal: the runner checks it (TeamCode's `X-TeamCode-Sha256`). */
private const val SHA256_HEADER = "X-Ostrom-Sha256"
