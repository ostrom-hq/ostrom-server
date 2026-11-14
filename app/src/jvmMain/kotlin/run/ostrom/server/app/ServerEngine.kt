// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import io.ktor.server.application.Application
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.netty.channel.ChannelOption

/**
 * The server's HTTP engine: Ktor's Netty, as TeamCode's app (docs/decisions/0001, step 3), listening on [host]:[port],
 * with `SO_REUSEADDR`. A container restarted after a crash binds its port at once: the connections of the one that
 * crashed wait in TIME_WAIT on that port, in the pod's network namespace that outlives it (chart-load 41612342204). It
 * never lets two live servers listen on one port.
 *
 * @param port The port; 0 for any free one (tests).
 * @param host The address listened on.
 * @param module The application.
 */
internal fun serverEngine(
    port: Int,
    host: String,
    module: suspend Application.() -> Unit,
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> = embeddedServer(
    Netty,
    configure = {
        connector {
            this.port = port
            this.host = host
        }
        configureBootstrap = { option(ChannelOption.SO_REUSEADDR, true) }
    },
    module = module,
)
