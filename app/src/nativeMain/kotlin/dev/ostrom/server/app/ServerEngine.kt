// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The server's HTTP engine: Ktor's CIO, listening on [host]:[port], with `SO_REUSEADDR`. A container restarted after a
 * crash binds its port at once: the connections of the one that crashed wait in TIME_WAIT on that port, in the pod's
 * network namespace that outlives it, and without the option the bind failed on EADDRINUSE for about a minute, a
 * second crash (chart-load 41612342204). It never lets two live servers listen on one port: Linux refuses a bind on a
 * listening socket's port with or without it.
 *
 * Calls run on Dispatchers.Default, one thread per core, not on the Dispatchers.IO that CIO uses: its threads (up to
 * 64) start on demand and never end, and each keeps memory of its own. 100 unauthenticated requests at once took the
 * server from 63 to 121 threads and OOMKilled it under 512 MiB (CHARGE 41637506577); on Default the same bursts kept
 * it between 78 and 96 (41637510769). CIO still reads and writes the sockets on IO. A call that blocks a thread (file
 * I/O) blocks one of few: the server's are short (the dev mail directory) or at startup.
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
    module = {
        intercept(ApplicationCallPipeline.Setup) { withContext(Dispatchers.Default) { proceed() } }
        module()
    },
)
