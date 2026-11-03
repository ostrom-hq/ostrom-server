// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer

/**
 * The server's HTTP engine: Ktor's CIO, listening on [host]:[port].
 *
 * @param port The port; 0 for any free one (tests).
 * @param host The address listened on.
 * @param module The application.
 */
internal fun serverEngine(
    port: Int,
    host: String,
    module: suspend Application.() -> Unit,
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> = embeddedServer(
    CIO,
    configure = {
        connector {
            this.port = port
            this.host = host
        }
    },
    module = module,
)
