// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import dev.ostrom.server.kernel.config.ConfigSource
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random

/**
 * The directory where server tests write every answer they receive, one JSON line each, for the CI to check them
 * against the bodies of the API contract (`ci/contract/check-answers.mjs`). Unset, nothing is written.
 */
const val CONTRACT_ANSWERS_DIR = "OSTROM_CONTRACT_ANSWERS_DIR"

/** One file per test process: two Gradle test tasks running side by side never write into the same file. */
private val answersFile = "answers-${Random.nextLong().toULong().toString(RADIX)}.jsonl"

private const val RADIX = 16

/** Lines of one process are written one at a time, whatever the number of calls in flight. */
private val writing = Mutex()

/**
 * Records every answer of this application in [CONTRACT_ANSWERS_DIR], when [environment] sets it: method, path,
 * status, content type and, for a body sent in one piece, the body. A streamed body (an SSE stream, a file) is
 * recorded without it.
 *
 * @param environment Where the directory is read from.
 */
fun Application.recordContractAnswers(environment: ConfigSource) {
    val directory = environment[CONTRACT_ANSWERS_DIR] ?: return
    SystemFileSystem.createDirectories(Path(directory))
    val file = Path(directory, answersFile)
    install(
        createApplicationPlugin("ContractAnswers") {
            on(ResponseBodyReadyForSend) { call, content ->
                val status = content.status ?: call.response.status() ?: HttpStatusCode.OK
                val bytes = (content as? OutgoingContent.ByteArrayContent)?.bytes()
                val type = content.contentType
                val line = buildJsonObject {
                    put("method", JsonPrimitive(call.request.httpMethod.value))
                    put("path", JsonPrimitive(call.request.path()))
                    put("status", JsonPrimitive(status.value))
                    put("contentType", if (type == null) JsonNull else JsonPrimitive(type.toString()))
                    put("body", if (bytes == null) JsonNull else JsonPrimitive(bytes.decodeToString()))
                }
                writing.withLock {
                    SystemFileSystem.sink(file, append = true).buffered().use { it.writeString("$line\n") }
                }
            }
        },
    )
}
