// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryNumber
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val TAG = "artifacts"

private const val ART_ID = "The artifact's id."

private const val BAD_FILES = "An invalid title, access or file path, no file, two files with one path, an " +
    "unknown encoding, malformed base64, or a file that looks like it holds a secret (a key or a token) " +
    "(`invalid_request`)."

private const val BUCKET_DOWN = "The artifact storage did not answer, or refused " +
    "(`artifact_storage_unavailable`)."

private const val FILE_PATHS = "The paths are relative, `/`-separated segments of letters, digits, `.`, `_` " +
    "and `-` (up to 200 characters), none `.` or `..`."

private const val INACTIVE = "Artifacts are not active for the organization: no bucket is stored " +
    "(`artifact_storage_missing`), the current one has not passed its test (`artifact_storage_unverified`), or " +
    "the switch is off (`artifacts_disabled`)."

private const val NF = "No such artifact, or one the caller may not read, or no organization for the caller " +
    "(`not_found`)."

private const val ORG_NF = "The caller acts for no organization (`not_found`)."

private const val PUBLISH_403 = "The credential lacks `artifacts:write` (`forbidden`), or only owners and " +
    "administrators publish and the caller is neither (`artifact_publish_forbidden`)."

private const val RATE = "The caller published more versions in the last hour than `quotaPublishesPerHour` " +
    "allows (`artifact_rate_limited`); `Retry-After` says in how many seconds to try again."

private const val READ_403 = "The credential lacks the `artifacts:read` scope (`forbidden`)."

private const val TOO_LARGE = "More than 200 files, a file over 5 MiB, a version over 20 MiB, or a body " +
    "past what a full version needs in base64 (`payload_too_large`)."

private const val WRITE_403 = "The credential lacks the `artifacts:write` scope (`forbidden`)."

/**
 * The artifacts module's operations: publishing artifacts and their versions, the chunked uploads of their
 * files, listing and reading them, sharing them, the reading tickets, the bucket and settings of the
 * organization, and the artifacts MCP server. The answers are the module's own types.
 */
internal fun ApiCatalog.artifactsOperations() {
    tag(TAG, "Pages and files that agents and people publish for the organization to read, and the bucket " +
        "that holds them.")
    publishOperations()
    versionPublishOperations()
    uploadOperations()
    uploadChunkOperations()
    listOperations()
    artifactChangeOperations()
    artifactStorageOperations()
}

/** Publishing an artifact. */
private fun ApiCatalog.publishOperations() {
    post("/api/v1/artifacts") {
        operationId = "publishArtifact"
        tag(TAG)
        summary = "Publish an artifact."
        description = "Makes an artifact owned by the caller, at version 1, with its files sent inline: " +
            "each file's `content` as text, or as base64 with `encoding`. `personal` (the default) and `team` " +
            "are published by any credential with `artifacts:write`; `link` (public) only by a person: a " +
            "browser session, the unlocked app or the CLI's sign-in, never a token a program can present (a " +
            "personal token, an agent token, a service key), so an agent never makes an artifact public. The " +
            "title has 1 to 200 characters without control characters. A version holds 1 to 200 files, each up " +
            "to 5 MiB and 20 MiB in all; larger files go through `createArtifactUpload`. " +
            FILE_PATHS +
            " A file that looks like it holds a secret refuses the whole publication. The page a reader opens " +
            "first is `index.html`, else the first HTML page, else the first Markdown page, else the first " +
            "file."
        parameters {
            organizationHeader()
        }
        body<PublishArtifactRequest>("The title, the access, and the files, each with its `content` as text " +
            "or as base64 with `encoding`.")
        responses {
            ok<ArtifactResponse>("The artifact, published at version 1.", HttpStatusCode.Created)
            badRequest(BAD_FILES)
            error(HttpStatusCode.Forbidden, PUBLISH_403)
            notFound(ORG_NF)
            conflict(INACTIVE)
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
            error(HttpStatusCode.TooManyRequests, RATE)
            error(HttpStatusCode.ServiceUnavailable, BUCKET_DOWN)
        }
    }
}

/** Publishing a version of an artifact. */
private fun ApiCatalog.versionPublishOperations() {
    post("/api/v1/artifacts/{id}/versions") {
        operationId = "publishArtifactVersion"
        tag(TAG)
        summary = "Publish a new version of an artifact."
        description = "With `uploadId`, the commit of an upload (`createArtifactUpload`): every file is " +
            "sent by that upload or already in the artifact's folder, and gives its `sha256` and `size` instead " +
            "of a content. `baseVersion` is the version the publisher started from (the live one by default) " +
            "and must still be live; `entry` names the page a reader opens first, an `.html`, `.htm` or `.md` " +
            "file of the version. A draft, made by an upload without an artifact, takes its `title` (required) " +
            "and its `access` with this first version; an existing artifact keeps its access, which only a " +
            "person changes with `updateArtifact`, and may be retitled. The same commit again within 10 minutes " +
            "(same credential, base version and files) answers 200 with the version it made, `replayed` true. " +
            "Without `uploadId`, the files are sent inline as in `publishArtifact`, and `entry`, `baseVersion`, " +
            "`title` and `access` are ignored. " +
            FILE_PATHS +
            " Only the owner publishes, and the versions beyond `maxVersionsPerArtifact` are pruned."
        parameters {
            organizationHeader()
            pathId("id", ART_ID)
        }
        body<PublishVersionRequest>("The files inline, or the `uploadId` to commit with each file's " +
            "`sha256` and `size`, and optionally `entry`, `baseVersion`, `title` and `access`.")
        responses {
            ok<ArtifactVersionResponse>("The version, published.", HttpStatusCode.Created)
            ok<ArtifactVersionResponse>("The same commit again within 10 minutes: the version it made, " +
                "`replayed` true.")
            badRequest("An invalid file, path, entry, title or access, a file without its `sha256` and " +
                "`size` in a commit, a draft without a title, an `access` that differs from the artifact's, or " +
                "a file that looks like it holds a secret (`invalid_request`).")
            error(HttpStatusCode.Forbidden, "Not the artifact's owner, or a credential without " +
                "`artifacts:write` (`forbidden`), only owners and administrators publish and the caller is " +
                "neither (`artifact_publish_forbidden`), or a draft made public by anything but a person " +
                "(`forbidden`).")
            notFound("No such artifact, or one the caller may not read, or no organization for the caller " +
                "(`not_found`), or the upload is closed, another credential's or another artifact's " +
                "(`artifact_upload_not_found`).")
            ok<ArtifactConflictResponse>("Artifacts are not active (`artifact_storage_missing`, " +
                "`artifact_storage_unverified` or `artifacts_disabled`), another version came first or the base " +
                "is not the live version (`artifact_conflict`; for a commit, `current` is the live version, for " +
                "the publisher to merge), a file of the commit is not in the artifact's folder " +
                "(`artifact_blob_missing`), its files are being cleaned up and `Retry-After` says when to try " +
                "again (`artifact_busy`), or a quota would be exceeded (`artifact_quota_exceeded`).",
                HttpStatusCode.Conflict)
            error(HttpStatusCode.PayloadTooLarge, TOO_LARGE)
            error(HttpStatusCode.TooManyRequests, RATE)
            error(HttpStatusCode.ServiceUnavailable, BUCKET_DOWN)
        }
    }
}

/** Announcing the files of the next version. */
private fun ApiCatalog.uploadOperations() {
    post("/api/v1/artifacts/uploads") {
        operationId = "createArtifactUpload"
        tag(TAG)
        summary = "Announce the files of the next version."
        description = "Scope `artifacts:write`. Announces the distinct files of a version by their SHA-256 " +
            "and size, 1 to 200 of them, each up to 16 MiB. Without `artifactId` a draft artifact is made for " +
            "the caller and deleted after 24 hours if never committed; with it, the caller must own the " +
            "artifact. `missing` lists the SHA-256 values the artifact's folder in the bucket does not hold " +
            "yet: send each in chunks of at most `chunkBytes` (1 MiB) with `putArtifactUploadChunk` before " +
            "`expiresAt` (one hour), then commit with `publishArtifactVersion`. A member holds at most 16 open " +
            "uploads and 20 unpublished drafts."
        parameters {
            organizationHeader()
        }
        body<CreateArtifactUploadRequest>("The artifact to add a version to (a draft is made without it) " +
            "and the distinct files, each with its SHA-256 in lower-case hex and its size.")
        responses {
            ok<ArtifactUploadResponse>("The upload: its id, the artifact, the files to send and when it " +
                "closes.", HttpStatusCode.Created)
            badRequest("Not 1 to 200 files, a SHA-256 that is not 64 lower-case hexadecimal characters, a " +
                "negative size, or one SHA-256 announced with two sizes (`invalid_request`).")
            error(HttpStatusCode.Forbidden, "The credential lacks `artifacts:write`, or the caller does not " +
                "own the artifact (`forbidden`).")
            notFound(NF)
            conflict("Artifacts are not active (`artifact_storage_missing`, `artifact_storage_unverified` " +
                "or `artifacts_disabled`), the artifact's files are being cleaned up and `Retry-After` says " +
                "when to try again (`artifact_busy`), or a new artifact would put the owner over " +
                "`quotaArtifactsPerMember` (`artifact_quota_exceeded`).")
            error(HttpStatusCode.PayloadTooLarge, "A file over 16 MiB (`artifact_file_too_large`).")
            error(HttpStatusCode.TooManyRequests, "The caller holds 16 open uploads or 20 unpublished " +
                "drafts (`artifact_uploads_busy`).")
        }
    }
}

/** Sending the chunks of an announced file. */
private fun ApiCatalog.uploadChunkOperations() {
    put("/api/v1/artifacts/uploads/{upload}/blobs/{sha256}/{n}") {
        operationId = "putArtifactUploadChunk"
        tag(TAG)
        summary = "Send one chunk of an announced file."
        description = "Scope `artifacts:write`, from the credential that opened the upload. The body is the " +
            "chunk's raw bytes (`application/octet-stream`), at most 1 MiB: every chunk but the last of a file " +
            "holds exactly `chunkBytes`, the last one the rest. Chunks may come in any order and in parallel; " +
            "they wait in the bucket. The last one to arrive assembles the file, checks its size and SHA-256, " +
            "scans a text file for secrets and stores it in the artifact's folder. A chunk of a file already " +
            "stored answers `complete` again."
        parameters {
            organizationHeader()
            pathId("upload", "The upload's id.")
            pathId("sha256", "The file's SHA-256 in lower-case hex, as announced.")
            pathId("n", "The chunk's index, from 0.")
        }
        responses {
            ok<ArtifactUploadChunkResponse>("Where the file stands: `complete` once it is whole and stored, " +
                "the chunks received and the total.")
            badRequest("`n` is not a whole number, or the chunk is not the size its index holds " +
                "(`invalid_request`).")
            error(HttpStatusCode.Forbidden, WRITE_403)
            notFound("The upload is closed, another credential's, or does not wait for this file " +
                "(`artifact_upload_not_found`), or the caller acts for no organization (`not_found`).")
            conflict(INACTIVE)
            error(HttpStatusCode.PayloadTooLarge, "A chunk over 1 MiB (`payload_too_large`), or a binary " +
                "file over 15 MiB, which ends the upload (`artifact_file_too_large`).")
            error(HttpStatusCode.UnprocessableEntity, "The assembled file is not the size or the SHA-256 " +
                "announced: send it again (`artifact_blob_mismatch`); or a text file looks like it holds a " +
                "secret, which ends the upload (`artifact_secret_detected`).")
            error(HttpStatusCode.ServiceUnavailable, BUCKET_DOWN)
        }
    }
}

/** Listing and reading artifacts. */
private fun ApiCatalog.listOperations() {
    get("/api/v1/artifacts") {
        operationId = "listArtifacts"
        tag(TAG)
        summary = "List the artifacts the caller can read."
        description = "Newest change first: theirs, those shared with them, and every `team` or `link` " +
            "artifact of the organization. A role opens nobody's `personal` artifact: an owner or an " +
            "administrator lists another member's only when it is shared with them. The filters combine (all of " +
            "them must match); a `cloudSession` or `launch` no artifact matches gives an empty page. `next` is " +
            "the id of the last artifact of a full page, so a page of exactly `limit` may be followed by an " +
            "empty one. Scope `artifacts:read`."
        parameters {
            organizationHeader()
            queryText("before", "The `next` of the previous page.")
            queryNumber("limit", "The page size, 1 to 100 (20 by default).")
            queryText("scope", "As TeamCode's gallery tabs: `team` (everything the caller can read, the " +
                "default), `mine` (the ones they own) or `shared` (the ones shared with them).")
            queryText("origin", "Where the artifact was created: `local_run` (an agent on a machine), " +
                "`cloud_session` (an agent of a cloud session) or `api` (a person or a program without an agent " +
                "token).")
            queryText("access", "Only the artifacts with this access: `personal`, `team` or `link`.")
            queryText("cloudSession", "Only what the launches of this cloud session published (an id).")
            queryText("launch", "Only what this launch published, a run of `ostrom <agent>` or a cloud " +
                "session's (an id).")
        }
        responses {
            ok<ArtifactsResponse>("A page of artifacts.")
            badRequest("A `limit` outside 1 to 100, a `before` that is not the id of an artifact of the " +
                "organization, an unknown `scope`, `origin` or `access`, or a `cloudSession` or `launch` that " +
                "is not an id of up to 64 characters (`invalid_request`).")
            error(HttpStatusCode.Forbidden, READ_403)
            notFound(ORG_NF)
            conflict(INACTIVE)
        }
    }
    get("/api/v1/artifacts/{id}") {
        operationId = "getArtifact"
        tag(TAG)
        summary = "Read an artifact."
        description = "Scope `artifacts:read`. 404 for a `personal` artifact the caller neither owns nor is " +
            "shared with, whatever their role, and for an id that is not one."
        parameters {
            organizationHeader()
            pathId("id", ART_ID)
        }
        responses {
            ok<ArtifactResponse>("The artifact, with what the caller is to it.")
            error(HttpStatusCode.Forbidden, READ_403)
            notFound(NF)
            conflict(INACTIVE)
        }
    }
}
