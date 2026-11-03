// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode

/**
 * [MachineApi] over HTTP: `POST /api/v1/runners/{id}/heartbeat` with the runner's key as a bearer token, no body. The
 * answer's `sessions` is not read: no session is scheduled on a registered runner yet.
 *
 * @param client An HTTP client that does not throw on an error status.
 * @param config The runner's configuration.
 */
class HttpMachineApi(private val client: HttpClient, private val config: MachineConfig) : MachineApi {
    override suspend fun heartbeat() {
        val response = client.post("${config.serverUrl}/api/v1/runners/${config.runnerId}/heartbeat") {
            bearerAuth(config.key)
        }
        when (response.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.Unauthorized -> throw RunnerKeyRefusedException()
            else -> error("Heartbeat answered ${response.status.value}.")
        }
    }
}
