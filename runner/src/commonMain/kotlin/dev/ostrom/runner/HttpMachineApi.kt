// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.runner

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

/**
 * [MachineApi] over HTTP: `POST /api/v1/runners/{id}/heartbeat` with the runner's key as a bearer token, and its report
 * as the body when it runs sessions.
 *
 * @param client An HTTP client that does not throw on an error status.
 * @param config The runner's configuration.
 */
class HttpMachineApi(private val client: HttpClient, private val config: MachineConfig) : MachineApi {
    override suspend fun heartbeat(report: MachineReport?): MachineWork {
        val response = client.post("${config.serverUrl}/api/v1/runners/${config.runnerId}/heartbeat") {
            bearerAuth(config.key)
            if (report != null) {
                contentType(ContentType.Application.Json)
                setBody(WIRE.encodeToString(MachineReport.serializer(), report))
            }
        }
        return when (response.status) {
            HttpStatusCode.OK -> WIRE.decodeFromString(MachineWork.serializer(), response.bodyAsText())
            HttpStatusCode.Unauthorized -> throw RunnerKeyRefusedException()
            else -> error("Heartbeat answered ${response.status.value}.")
        }
    }

    private companion object {
        /** The answer also has `sessions`, which a runner does not read. */
        val WIRE = Json { ignoreUnknownKeys = true }
    }
}
