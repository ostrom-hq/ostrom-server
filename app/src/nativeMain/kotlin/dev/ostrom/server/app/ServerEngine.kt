// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer

/**
 * The server's HTTP engine: Ktor's CIO, listening on [host]:[port], with `SO_REUSEADDR`. A container restarted after a
 * crash binds its port at once: the connections of the one that crashed wait in TIME_WAIT on that port, in the pod's
 * network namespace that outlives it, and without the option the bind failed on EADDRINUSE for about a minute, a
 * second crash (chart-load 41612342204). It never lets two live servers listen on one port: Linux refuses a bind on a
 * listening socket's port with or without it.
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
        reuseAddress = true
    },
    module = module,
)
