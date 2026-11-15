// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

// Files sent to sessions (`runners.cloud.files.v1`, TeamCode's `/cloud/sessions/{id}/files`, `/cloud/files/policy`
// and `/cloud/runner/files/{id}`).

/**
 * `POST /api/v1/cloud/sessions/{id}/files` (`uploadCloudFile`): the raw bytes of one file.
 *
 * @property parent The session.
 * @property name The file's name, a label only: cleaned by the server.
 */
@Serializable
@Resource("files")
class CloudFilesApi(val parent: CloudSessionsApi.Id, val name: String? = null)

/** `GET /api/v1/cloud/files/policy` (`getCloudFilePolicy`). */
@Serializable
@Resource("/api/v1/cloud/files/policy")
class CloudFilePolicyApi

/**
 * `GET /api/v1/cloud/runner/files/{id}` (`cloudRunnerFile`): a file's bytes, for the runner of the execution its
 * message was delivered to.
 *
 * @property id The file.
 * @property sessionId The session.
 * @property epoch The execution.
 */
@Serializable
@Resource("/api/v1/cloud/runner/files/{id}")
class RunnerFileApi(val id: String, val sessionId: String = "", val epoch: Long = 0)

/** ostrom-core's `CloudFile`. */
@Serializable
data class CloudFileDto(
    val id: String,
    val sessionId: String,
    val name: String,
    val size: Long,
    val contentType: String,
    val sha256: String,
    val createdAt: String,
    val expiresAt: String,
)

/** ostrom-core's `CloudFilePolicy`. */
@Serializable
data class CloudFilePolicyDto(
    val available: Boolean,
    val reason: String? = null,
    val maxFileBytes: Long,
    val maxMessageBytes: Long,
    val maxFilesPerMessage: Int,
    val types: List<String>,
)

/**
 * A file a message carries, as its runner downloads it.
 *
 * @property sha256 The SHA-256 of its bytes, in hexadecimal: the runner checks it.
 */
@Serializable
data class RunnerFileDto(val id: String, val name: String, val size: Long, val contentType: String, val sha256: String)
