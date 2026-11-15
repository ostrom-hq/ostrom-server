// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.http.HttpStatusCode
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.headerText
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryNumber
import run.ostrom.server.kernel.http.docs.queryText

private const val FILES = "`runners.cloud.files.v1` (TeamCode's"

/**
 * The files sent to cloud sessions (`runners.cloud.files.v1`): mounted with the files service only, hence
 * conditional.
 */
internal fun ApiCatalog.cloudFileOperations() = conditionally {
    cloudFileUploadOperations()
    runnerFileOperations()
}

/** The organization's policy and the upload of one file. */
private fun ApiCatalog.cloudFileUploadOperations() {
    get("/api/v1/cloud/files/policy") {
        operationId = "getCloudFilePolicy"
        tag(TAG)
        summary = "Whether the caller's organization can send files to sessions now, and its limits."
        description = "$FILES `GET /cloud/files/policy`). $ORG_RESOLUTION"
        parameters {
            organizationHeader()
        }
        responses {
            ok<CloudFilePolicyDto>("The policy: whether files can be sent, why not, the limits and the types.")
        }
    }
    post("/api/v1/cloud/sessions/{id}/files") {
        operationId = "uploadCloudFile"
        tag(TAG)
        summary = "Upload a file for the next message (an image, a PDF or UTF-8 text)."
        description = "$FILES `POST /cloud/sessions/{id}/files`). The body is the file's raw bytes, authorized like " +
            "a message. The type is recognized from the bytes (PNG, JPEG, GIF, WebP, PDF, else UTF-8 text without " +
            "NUL). `Idempotency-Key` makes a retry of the same name and bytes return the first file (200, " +
            "`Idempotency-Replayed: true`). Name it in a message's `fileIds` before `expiresAt`; kept 24 hours at " +
            "most, deleted once the host has it."
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
            queryText("name", "The file's name, a label only: cleaned by the server. Sent once.")
            headerText("Idempotency-Key", "1 to 200 visible ASCII characters; the same key returns the first file.")
        }
        responses {
            ok<CloudFileDto>("The file, stored until it is sent or expires.", HttpStatusCode.Created)
            ok<CloudFileDto>("The first file of this `Idempotency-Key`.")
            badRequest("An empty file (`empty_file`), a name sent twice or unusable (`invalid_file_name`), or a " +
                "malformed `Idempotency-Key` (`invalid_idempotency_key`).")
            notFound(NO_SESSION)
            conflict("No tested artifact bucket for the organization (`file_storage_not_configured`), or the " +
                "first upload of this `Idempotency-Key` is still running (`file_upload_in_progress`): retry later.")
            error(HttpStatusCode.PayloadTooLarge, "Above the organization's file limit (`file_too_large`).")
            error(HttpStatusCode.UnsupportedMediaType, "A type that is not allowed (`file_type_not_allowed`).")
            error(HttpStatusCode.UnprocessableEntity, "The key was used with another name or other bytes " +
                "(`idempotency_conflict`).")
            error(HttpStatusCode.TooManyRequests, "Over a quota (`upload_quota`, with `Retry-After`): 20 unsent " +
                "files or 100 MiB per session, 1 GiB and 500 files per member per UTC day.")
            error(HttpStatusCode.ServiceUnavailable, "The bucket cannot be reached (`file_storage_unavailable`).")
        }
    }
}

/** The runner's download of a file a message delivered to its execution. */
private fun ApiCatalog.runnerFileOperations() {
    get("/api/v1/cloud/runner/files/{id}") {
        operationId = "cloudRunnerFile"
        tag(TAG)
        summary = "The bytes of a file a message delivered to this very execution carries."
        description = "$FILES `GET /cloud/runner/files/{id}`). $RUNNER_TOKEN The bytes come with " +
            "`X-Ostrom-Sha256`, their SHA-256 in hexadecimal, which the runner checks."
        parameters {
            pathId("id", "The file's id.")
            queryText("sessionId", SESSION_ID)
            queryNumber("epoch", "The execution's epoch.")
        }
        responses {
            empty("The file's bytes (`application/octet-stream`).", HttpStatusCode.OK)
            error(HttpStatusCode.Unauthorized, RUNNER_UNKNOWN)
            notFound("Any file a message delivered to this execution does not carry (`not_found`).")
            conflict("$FENCED Or the file's bytes are not all stored (`file_unavailable`).")
            error(HttpStatusCode.Gone, "The file expired (`file_expired`).")
            error(HttpStatusCode.ServiceUnavailable, "The bucket cannot be reached (`file_storage_unavailable`).")
        }
    }
}
