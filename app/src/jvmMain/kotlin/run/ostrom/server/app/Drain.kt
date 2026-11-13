// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.app

import run.ostrom.server.kernel.config.ServerConfig
import run.ostrom.server.kernel.http.CallsInProgress
import run.ostrom.server.kernel.http.Readiness
import run.ostrom.server.kernel.logging.Logger
import sun.misc.Signal
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin

/**
 * Blocks until SIGTERM or SIGINT arrives, and replaces the JVM's handling of both (an immediate exit through the
 * shutdown hooks) by the drain of [drain].
 *
 * @return The signal's name, `SIGTERM` or `SIGINT`.
 */
internal fun awaitStopSignal(): String {
    val received = LinkedBlockingQueue<String>()
    for (name in listOf("TERM", "INT")) Signal.handle(Signal(name)) { received.offer("SIG${it.name}") }
    return received.take()
}

/**
 * The drain after [signal] (audit SD-09): `/ready` answers 503 for [ServerConfig.drainDelay] while requests are still
 * served, then the server waits up to [ServerConfig.drainGrace] for the calls in flight (a model's stream, a gate's
 * wait) to end. The caller then stops the engine, which cuts the live streams their clients reopen.
 */
internal fun drain(signal: String, config: ServerConfig, server: Koin, logger: Logger) {
    logger.info(
        "server draining",
        mapOf(
            "signal" to signal,
            "delay_s" to config.drainDelay.inWholeSeconds,
            "grace_s" to config.drainGrace.inWholeSeconds,
        ),
    )
    server.get<Readiness>().drain()
    val ended = runBlocking {
        delay(config.drainDelay)
        server.get<CallsInProgress>().awaitCallsEnded(config.drainGrace)
    }
    if (!ended) {
        logger.warn("calls still running after the grace", mapOf("grace_s" to config.drainGrace.inWholeSeconds))
    }
}
