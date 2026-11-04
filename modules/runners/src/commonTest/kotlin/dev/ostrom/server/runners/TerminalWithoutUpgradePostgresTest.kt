// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners

import dev.ostrom.server.kernel.http.ApiError
import dev.ostrom.server.runners.fakes.testIdentities
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A GET that does not ask for a WebSocket upgrade on a terminal route is always the same bad request, an `ApiError`
 * `invalid_request` (contract `attachCloudTerminal`, `attachRunnerTerminal`): whoever asks, whatever the session,
 * before any credential is read.
 */
class TerminalWithoutUpgradePostgresTest {
    @Test
    fun aPlainGetIsAlwaysAnInvalidRequest() = serverTest(listOf(RunnersModule), testIdentities) { server ->
        val asks = listOf(
            "/api/v1/cloud/sessions/00000000-0000-0000-0000-000000000001/terminal" to "alice",
            "/api/v1/cloud/sessions/not-a-uuid/terminal" to "carol",
            "/api/v1/cloud/sessions/00000000-0000-0000-0000-000000000001/terminal?ticket=nope" to null,
            "/api/v1/cloud/runner/terminal" to null,
            "/api/v1/cloud/runner/terminal?sessionId=00000000-0000-0000-0000-000000000001&epoch=1" to "runner",
        )
        for ((path, token) in asks) {
            val response = server.client.get(path) { if (token != null) bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, response.status, path)
            assertEquals("invalid_request", response.body<ApiError>().error.type, path)
        }
    }
}
