// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.RunnerExecutionRequest
import run.ostrom.server.runners.domain.RunnerInferenceUseCase
import io.ktor.http.HttpHeaders
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The execution's inference credential (`runners.cloud.inference.v1`), on the runner channel: the runner's credential
 * is checked before the body is read (S2-46), and the credential is returned only to the runner of the current
 * execution, never cached, stored in clear or logged.
 *
 * @param dependencies The runner channel's checks.
 * @param use Issues the credential.
 */
internal fun Route.inferenceRoute(dependencies: CloudSessionsRoutesDependencies, use: RunnerInferenceUseCase) {
    post<RunnerInferenceApi> {
        val token = call.runnerToken()
        dependencies.checkRunnerToken(token)
        val request = call.receive<RunnerExecutionBody>()
        val sessionId = Uuid.parseOrNull(request.sessionId)
            ?: throw InvalidCloudSessionRequestException("Malformed session id.")
        val issued = use(token, RunnerExecutionRequest(sessionId, request.epoch))
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val lifetime = issued.lifetime.inWholeMilliseconds
        call.respond(RunnerInferenceCredentialResponse(issued.token, issued.expiresAt.toString(), lifetime))
    }
}
