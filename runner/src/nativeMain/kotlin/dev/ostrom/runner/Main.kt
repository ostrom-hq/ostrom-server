package dev.ostrom.runner

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.posix.getenv
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.stderr
import kotlin.system.exitProcess
import kotlin.time.Clock

/** The runner's entry point (`ostrom-runner`, the session pod's command). */
fun main() {
    val environment = readEnvironment()
    val config = try {
        RunnerConfig.from(environment) { path -> SystemFileSystem.source(Path(path)).buffered().use { it.readString() } }
    } catch (error: IllegalArgumentException) {
        log("invalid configuration: ${error.message}")
        exitProcess(2)
    }
    log("starting $config")
    val code = runBlocking {
        val client = HttpClient(CIO) {
            expectSuccess = false
            install(WebSockets)
            install(HttpTimeout) { connectTimeoutMillis = 10_000 }
        }
        try {
            Runner(config, HttpServerApi(client, config, this), PtyTerminal, environment, Clock.System, ::log).run()
        } finally {
            client.close()
        }
    }
    log("exiting with $code")
    exitProcess(code)
}

private fun log(line: String) {
    fprintf(stderr, "%s\n", "ostrom-runner: $line")
    fflush(stderr)
}

/** The process environment: `/proc/self/environ` on Linux, a few known variables elsewhere (development). */
private fun readEnvironment(): Map<String, String> {
    val proc = Path("/proc/self/environ")
    if (SystemFileSystem.exists(proc)) {
        return parseEnvironment(SystemFileSystem.source(proc).buffered().use { it.readString() })
    }
    return KNOWN_VARIABLES.mapNotNull { name -> getenv(name)?.toKString()?.let { name to it } }.toMap()
}

private val KNOWN_VARIABLES = listOf(
    "PATH", "HOME", "LANG", "USER", "SHELL", "OSTROM_SERVER_URL", "OSTROM_SESSION_ID", "OSTROM_EPOCH", "OSTROM_AGENT",
    "OSTROM_AGENT_COMMAND", "OSTROM_REPOSITORY_URL", "OSTROM_REPOSITORY_REF", "OSTROM_RUNNER_TOKEN", "OSTROM_RUNNER_TOKEN_FILE",
    "OSTROM_WORKSPACE",
)
