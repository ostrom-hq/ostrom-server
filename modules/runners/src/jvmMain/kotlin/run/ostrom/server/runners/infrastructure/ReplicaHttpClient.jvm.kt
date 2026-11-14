// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

internal actual fun replicaHttpClient(): HttpClient = HttpClient(CIO) { install(WebSockets) }
