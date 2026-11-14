// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills.presentation

import run.ostrom.server.runners.domain.RunnerUnauthenticatedException
import run.ostrom.server.skills.domain.InvalidCatalogRequestException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import kotlin.uuid.Uuid

/**
 * The runner token of a runner call to the skill or hook delivery, `Authorization: Bearer ostrom_runner_…`, read as
 * the runners module reads it.
 *
 * @throws RunnerUnauthenticatedException When absent.
 */
fun ApplicationCall.runnerToken(): String {
    val token = request.headers[HttpHeaders.Authorization].orEmpty().substringAfter("Bearer ", "").trim()
    if (token.isEmpty()) throw RunnerUnauthenticatedException()
    return token
}

/**
 * A runner call's session id.
 *
 * @throws InvalidCatalogRequestException When it is not a UUID.
 */
fun String.toSessionId(): Uuid = Uuid.parseOrNull(this)
    ?: throw InvalidCatalogRequestException("invalid_runner_request", "sessionId: a session's id.")
