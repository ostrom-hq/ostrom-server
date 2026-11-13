// SPDX-License-Identifier: Apache-2.0
package run.ostrom.mcphost

import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.cio.CIO
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.system.exitProcess
import kotlin.time.Clock

/**
 * The entry point of the image: reads `OSTROM_MCP_HOST_*`, serves host protocol v1, and on SIGTERM drains (`/ready`
 * fails, running operations get [HostConfig.drainMillis]) before every process is stopped. A bad configuration exits 2.
 */
fun main() {
    val config = try {
        HostConfig.fromEnvironment(System.getenv())
    } catch (error: IllegalArgumentException) {
        System.err.println("ostrom-mcp-host: ${error.message}")
        exitProcess(EXIT_BAD_CONFIGURATION)
    }
    val events = EventSink { log(it) }
    val table = JvmProcessTable()
    val platform = HostPlatform(
        spawner = JvmProcessSpawner(table),
        table = table,
        files = JvmHostFiles(),
        events = events,
        // Three blocking readers and writers per process, beyond the 64 threads of Dispatchers.IO the engine uses.
        io = Dispatchers.IO.limitedParallelism(config.maxProcesses * THREADS_PER_PROCESS + THREADS_SLACK),
        now = { Clock.System.now().toEpochMilliseconds() },
    )
    val failures = CoroutineExceptionHandler { _, error ->
        log(buildJsonObject { put("event", "job_failed"); put("error", error::class.simpleName) })
    }
    val supervisor = Supervisor(config, platform, Dispatchers.Default + failures)
    log(buildJsonObject { put("event", "host_starting"); put("version", HOST_VERSION); put("port", config.port) })
    embeddedServer(
        CIO,
        configure = {
            connector {
                port = config.port
                host = config.address
            }
            reuseAddress = true
        },
    ) {
        // SIGTERM: drain before the engine closes its connections, then stop every process.
        monitor.subscribe(ApplicationStopPreparing) {
            runBlocking {
                supervisor.drain()
                supervisor.shutdown()
            }
        }
        mcpHost(config, supervisor) { error ->
            log(buildJsonObject { put("event", "unexpected"); put("error", error) })
        }
    }.start(wait = true)
}

private val output = Any()

/** One JSON line on stdout: never a secret, an argument, an input or an output. */
private fun log(event: JsonObject) {
    val line = buildJsonObject {
        put("time", Clock.System.now().toString())
        event.forEach { (key, value) -> put(key, value) }
    }.toString()
    synchronized(output) { println(line) }
}

private const val EXIT_BAD_CONFIGURATION = 2
private const val THREADS_PER_PROCESS = 3
private const val THREADS_SLACK = 8
