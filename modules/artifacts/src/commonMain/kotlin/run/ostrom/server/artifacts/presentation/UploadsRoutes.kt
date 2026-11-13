// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.presentation

import run.ostrom.server.artifacts.domain.InvalidArtifactRequestException
import run.ostrom.server.artifacts.domain.uploads.AnnouncedFile
import run.ostrom.server.artifacts.domain.uploads.ArtifactUploadNotFoundException
import run.ostrom.server.artifacts.domain.uploads.UploadArtifactUseCase
import run.ostrom.server.artifacts.domain.uploads.UploadLimits
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.bodyLimit
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The chunked uploads of artifact files (TeamCode's `POST /artifacts/uploads` and
 * `PUT /artifacts/uploads/{upload}/blobs/{sha256}/{n}`), for a credential with `artifacts:write`.
 *
 * @param uploads The use case.
 * @param orgResolver The member a request acts for.
 */
fun Route.artifactUploadRoutes(uploads: UploadArtifactUseCase, orgResolver: PrincipalOrgResolver) {
    authenticated {
        post<ArtifactUploadsApi> {
            val caller = orgResolver.artifactCallerOf(call.requirePrincipal(), ARTIFACTS_WRITE)
            val request = call.receive<CreateArtifactUploadRequest>()
            val upload = uploads.open(
                caller,
                request.artifactId?.let { artifactId(it) },
                request.files.map { AnnouncedFile(it.sha256, it.size) },
            )
            call.respond(
                HttpStatusCode.Created,
                ArtifactUploadResponse(
                    uploadId = upload.id.toString(),
                    artifactId = upload.artifactId.toString(),
                    missing = upload.missing,
                    chunkBytes = UploadLimits.CHUNK_BYTES,
                    expiresAt = upload.expiresAt.toString(),
                ),
            )
        }
        bodyLimit(UploadLimits.CHUNK_BYTES.toLong()) {
            put<ArtifactUploadsApi.Chunk> { params ->
                val caller = orgResolver.artifactCallerOf(call.requirePrincipal(), ARTIFACTS_WRITE)
                val index = params.n.toIntOrNull() ?: throw InvalidArtifactRequestException("n is a whole number.")
                val receipt = uploads.chunk(
                    caller,
                    Uuid.parseOrNull(params.upload) ?: throw ArtifactUploadNotFoundException(),
                    params.sha256,
                    index,
                    call.receive<ByteArray>(),
                )
                call.respond(ArtifactUploadChunkResponse(receipt.complete, receipt.received, receipt.total))
            }
        }
    }
}
