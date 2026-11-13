// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain.uploads

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/** The upload does not exist, closed, or is another credential's; or the file is not one it waits for. */
class ArtifactUploadNotFoundException : OstromException(
    ErrorKind.NOT_FOUND,
    "Upload not found: it closed after an hour, or another credential opened it. Open a new one.",
    "artifact_upload_not_found",
)

/** The member holds too many open uploads or unpublished drafts. */
class ArtifactUploadsBusyException : OstromException(
    ErrorKind.RATE_LIMITED,
    "Too many uploads are open: commit or let them close (an hour), then try again.",
    "artifact_uploads_busy",
)

/** The assembled file is not the one announced: its chunks are forgotten, to send again. */
class ArtifactBlobMismatchException : OstromException(
    ErrorKind.UNPROCESSABLE,
    "The file sent does not match its size or its SHA-256: send it again.",
    "artifact_blob_mismatch",
)

/**
 * A text file holds what looks like a credential: refused before it reaches the bucket, and the upload ends.
 *
 * @param sha256 The file, never the value found.
 */
class ArtifactSecretDetectedException(sha256: String) : OstromException(
    ErrorKind.UNPROCESSABLE,
    "The file $sha256 looks like it holds a secret (a key or a token): remove it and publish again.",
    "artifact_secret_detected",
)

/** A file is larger than a version may hold: the upload ends. */
class ArtifactFileTooLargeException : OstromException(
    ErrorKind.PAYLOAD_TOO_LARGE,
    "A file holds at most ${UploadLimits.MAX_TEXT_BYTES / UploadLimits.CHUNK_BYTES} MiB of text or " +
        "${UploadLimits.MAX_BINARY_BYTES / UploadLimits.CHUNK_BYTES} MiB of binary.",
    "artifact_file_too_large",
)

/**
 * A file of the version is neither sent by the upload nor in the artifact's folder (at its size).
 *
 * @param sha256 The file.
 */
class ArtifactBlobMissingException(sha256: String) : OstromException(
    ErrorKind.CONFLICT,
    "The file $sha256 was not sent by this upload and the artifact does not hold it: send it, then commit again.",
    "artifact_blob_missing",
)
