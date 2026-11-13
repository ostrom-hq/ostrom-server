// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.presentation

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactCaller
import run.ostrom.server.artifacts.domain.ArtifactConflictException
import run.ostrom.server.artifacts.domain.ArtifactNotFoundException
import run.ostrom.server.artifacts.domain.ArtifactRelation
import run.ostrom.server.artifacts.domain.ArtifactRules
import run.ostrom.server.artifacts.domain.ArtifactTicketsRateLimitedException
import run.ostrom.server.artifacts.domain.ArtifactVersion
import run.ostrom.server.artifacts.domain.InvalidArtifactRequestException
import run.ostrom.server.artifacts.domain.uploads.ArtifactUploadNotFoundException
import run.ostrom.server.artifacts.domain.uploads.CommitArtifactUploadUseCase
import run.ostrom.server.artifacts.domain.uploads.CommittedFile
import run.ostrom.server.artifacts.domain.uploads.UploadCommit
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.ErrorDetail
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.kernel.http.bodyLimit
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.SerializationException
import kotlin.uuid.Uuid

/**
 * The artifact API under `/api/v1/artifacts`, for members and their CLI, agents and apps.
 *
 * @param dependencies The use cases.
 */
@Suppress("LongMethod") // One route per contract operation, each a few lines.
fun Route.artifactsRoutes(dependencies: ArtifactsRoutesDependencies) = with(dependencies) {
    val mcp = ArtifactsMcpServer(dependencies)
    authenticated {
        // Publishing carries the files of a version, base64 in JSON: more than the 1 MiB default (S2-46).
        bodyLimit(PUBLISH_BODY_LIMIT) {
            post<ArtifactsMcpApi> {
                val principal = call.requirePrincipal()
                val answer = mcp.handle(call.receiveText()) { scope ->
                    orgResolver.artifactCallerOf(principal, scope)
                }
                if (answer == null) {
                    call.respond(HttpStatusCode.Accepted)
                } else {
                    call.respondText(answer.toString(), ContentType.Application.Json)
                }
            }
            post<ArtifactsApi> {
                val caller = orgResolver.artifactCallerOf(call.requirePrincipal(), ARTIFACTS_WRITE)
                val request = call.receive<PublishArtifactRequest>()
                val artifact = publish.create(caller, request.title, request.access, request.files.toUploads())
                call.respond(HttpStatusCode.Created, toResponse(artifact, caller))
            }
            post<ArtifactsApi.Id.Versions> { params ->
                val caller = orgResolver.artifactCallerOf(call.requirePrincipal(), ARTIFACTS_WRITE)
                val request = call.receive<PublishVersionRequest>()
                val id = artifactId(params.parent.id)
                if (request.uploadId == null) {
                    val version = publish.addVersion(caller, id, request.files.toUploads())
                    call.respond(HttpStatusCode.Created, version.toResponse())
                } else {
                    call.respondCommit(commit, caller, id, request.toCommit(request.uploadId))
                }
            }
        }
        get<ArtifactsApi> { params ->
            val caller = orgResolver.artifactCallerOf(call.requirePrincipal(), ARTIFACTS_READ)
            val page = read.list(caller, params.before?.let { parseId(it) }, params.limit)
            val limit = params.limit ?: DEFAULT_LIMIT
            val next = page.lastOrNull()?.let { last -> last.id.toString().takeIf { page.size == limit } }
            call.respond(ArtifactsResponse(page.map { toResponse(it, caller) }, next))
        }
        get<ArtifactsApi.Id> { params ->
            val caller = orgResolver.artifactCallerOf(call.requirePrincipal(), ARTIFACTS_READ)
            call.respond(toResponse(read.get(caller, artifactId(params.id)), caller))
        }
        patch<ArtifactsApi.Id> { params ->
            val caller = orgResolver.artifactCallerOf(call.requirePrincipal(), ARTIFACTS_WRITE)
            val request = call.receive<UpdateArtifactRequest>()
            val updated = manage.update(caller, artifactId(params.id), request.title, request.access)
            call.respond(toResponse(updated, caller))
        }
        delete<ArtifactsApi.Id> { params ->
            val caller = orgResolver.artifactCallerOf(call.requirePrincipal(), ARTIFACTS_WRITE)
            manage.delete(caller, artifactId(params.id))
            call.respond(HttpStatusCode.NoContent)
        }
        get<ArtifactsApi.Id.Versions> { params ->
            val caller = orgResolver.artifactCallerOf(call.requirePrincipal(), ARTIFACTS_READ)
            val versions = read.versions(caller, artifactId(params.parent.id))
            call.respond(ArtifactVersionsResponse(versions.map { it.toResponse() }))
        }
        get<ArtifactsApi.Id.Versions.File> { params ->
            val caller = orgResolver.artifactCallerOf(call.requirePrincipal(), ARTIFACTS_READ)
            val number = params.number.toIntOrNull() ?: throw ArtifactNotFoundException()
            val file = read.file(caller, artifactId(params.parent.parent.id), number, params.path)
            val name = (params.path ?: "file").substringAfterLast('/')
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString(),
            )
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("Content-Security-Policy", "sandbox; default-src 'none'")
            call.respondBytes(file.bytes, ContentType.parse(file.contentType))
        }
        get<ArtifactsApi.Id.Members> { params ->
            val caller = orgResolver.artifactCallerOf(call.requirePrincipal(), ARTIFACTS_READ)
            call.respond(ArtifactMembersResponse(manage.members(caller, artifactId(params.parent.id))))
        }
        put<ArtifactsApi.Id.Members.Member> { params ->
            val caller = orgResolver.artifactCallerOf(call.requirePrincipal(), ARTIFACTS_WRITE)
            manage.share(caller, artifactId(params.parent.parent.id), params.login, share = true)
            call.respond(HttpStatusCode.NoContent)
        }
        delete<ArtifactsApi.Id.Members.Member> { params ->
            val caller = orgResolver.artifactCallerOf(call.requirePrincipal(), ARTIFACTS_WRITE)
            manage.share(caller, artifactId(params.parent.parent.id), params.login, share = false)
            call.respond(HttpStatusCode.NoContent)
        }
        post<ArtifactsApi.Id.Tickets> { params ->
            val caller = orgResolver.artifactCallerOf(call.requirePrincipal(), ARTIFACTS_READ)
            val request = parseTicketRequest(call.receiveText())
            val ticket = try {
                view.ticket(caller, artifactId(params.parent.id), request.version)
            } catch (limited: ArtifactTicketsRateLimitedException) {
                call.response.header(HttpHeaders.RetryAfter, limited.retryAfterSeconds.toString())
                throw limited
            }
            call.respond(
                HttpStatusCode.Created,
                ArtifactTicketResponse(
                    artifactId = ticket.artifactId.toString(),
                    version = ticket.version,
                    url = ticket.contentUrl,
                    contentUrl = ticket.contentUrl,
                    expiresAt = ticket.expiresAt.toString(),
                ),
            )
        }
    }
}

private const val DEFAULT_LIMIT = 20

/**
 * The body of a publication: a whole version ([ArtifactRules.MAX_VERSION_BYTES]) in base64 (4 bytes for 3), plus 1 MiB
 * for the JSON around it (S2-46). The use case still checks each file and the version's total.
 */
private const val PUBLISH_BODY_LIMIT = ArtifactRules.MAX_VERSION_BYTES / 3 * 4 + (1L shl 20)

/**
 * Reads a ticket request; an empty body asks for the latest version.
 *
 * @throws InvalidArtifactRequestException When the body is not such a request.
 */
internal fun parseTicketRequest(body: String): TicketRequest = if (body.isBlank()) {
    TicketRequest()
} else {
    try {
        OstromJson.decodeFromString<TicketRequest>(body)
    } catch (_: SerializationException) {
        throw InvalidArtifactRequestException("The request is malformed.")
    }
}

/** A malformed id names no artifact: 404, like an unknown one. */
internal fun artifactId(raw: String): Uuid = Uuid.parseOrNull(raw) ?: throw ArtifactNotFoundException()

private fun parseId(raw: String): Uuid =
    Uuid.parseOrNull(raw) ?: throw InvalidArtifactRequestException("Malformed artifact id.")

/** Maps an artifact to the API, as [caller] sees it. */
internal fun ArtifactsRoutesDependencies.toResponse(artifact: Artifact, caller: ArtifactCaller) = ArtifactResponse(
    id = artifact.id.toString(),
    title = artifact.title,
    owner = artifact.ownerId,
    access = artifact.access.wire,
    relation = ArtifactRelation.of(caller, artifact).wire,
    latestVersion = artifact.latestVersion,
    url = viewerUrl(artifact),
    publicUrl = view.publicUrl(artifact),
    createdAt = artifact.createdAt.toString(),
    updatedAt = artifact.updatedAt.toString(),
)

/** The console's page of an artifact. */
internal fun ArtifactsRoutesDependencies.viewerUrl(artifact: Artifact) = "${locations.viewerUrl}/${artifact.id}"

/** Maps a version to the API. */
internal fun ArtifactVersion.toResponse() = ArtifactVersionResponse(
    number = number,
    files = files.map { it.path },
    entry = entry,
    createdAt = createdAt.toString(),
    createdBy = createdBy,
    digest = digest,
    baseVersion = baseVersion,
)

/**
 * The commit of [uploadId]: each file by its SHA-256 and size.
 *
 * @throws InvalidArtifactRequestException When a file lacks them.
 * @throws run.ostrom.server.artifacts.domain.uploads.ArtifactUploadNotFoundException When the upload id is malformed.
 */
internal fun PublishVersionRequest.toCommit(uploadId: String) = UploadCommit(
    uploadId = Uuid.parseOrNull(uploadId) ?: throw ArtifactUploadNotFoundException(),
    files = files.map { file ->
        val sha256 = file.sha256
        val size = file.size
        if (sha256 == null || size == null) {
            throw InvalidArtifactRequestException(
                "${file.path.take(ArtifactRules.MAX_PATH_LENGTH)} needs its sha256 and size to commit an upload.",
            )
        }
        CommittedFile(file.path, sha256, size)
    },
    entry = entry,
    baseVersion = baseVersion,
    title = title,
    access = access,
)

// A commit's answer: its version (200 for a replay), or with a conflict the live version (TeamCode's
// `{error, current}`).
private suspend fun ApplicationCall.respondCommit(
    commit: CommitArtifactUploadUseCase,
    caller: ArtifactCaller,
    id: Uuid,
    upload: UploadCommit,
) {
    val committed = try {
        commit.commit(caller, id, upload)
    } catch (conflict: ArtifactConflictException) {
        val error = ErrorDetail(conflict.type, conflict.message)
        respond(HttpStatusCode.Conflict, ArtifactConflictResponse(error, conflict.current?.toResponse()))
        return
    }
    val status = if (committed.replayed) HttpStatusCode.OK else HttpStatusCode.Created
    respond(status, committed.version.toResponse().copy(replayed = committed.replayed))
}
