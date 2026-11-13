// SPDX-License-Identifier: Apache-2.0
package run.ostrom.runner

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [MachineApi] over HTTP: `POST /api/v1/runners/{id}/heartbeat` with the runner's key as a bearer token, and its report
 * as the body when it runs sessions. A heartbeat that may wait asks `?wait=` and is given that long more than the
 * client's own timeout ([WAIT_SLACK] in all), as the server holds it.
 *
 * @param client An HTTP client that does not throw on an error status.
 * @param config The runner's configuration.
 */
class HttpMachineApi(private val client: HttpClient, private val config: MachineConfig) : MachineApi {
    override suspend fun heartbeat(report: MachineReport?, wait: Duration): MachineWork {
        val query = if (wait.isPositive()) "?wait=${wait.inWholeSeconds}" else ""
        val response = client.post("${config.serverUrl}/api/v1/runners/${config.runnerId}/heartbeat$query") {
            bearerAuth(config.key)
            if (wait.isPositive()) timeout { requestTimeoutMillis = (wait + WAIT_SLACK).inWholeMilliseconds }
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

    /** Constants of the enclosing type. */
    companion object {
        /** A waiting heartbeat's timeout beyond its wait: the answer's way back, as an answer at once is given. */
        val WAIT_SLACK: Duration = 10.seconds

        /** The answer also has `sessions`, which a runner does not read; an unknown capacity is left out. */
        private val WIRE = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
