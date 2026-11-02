// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class)

package dev.ostrom.runner

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.staticCFunction
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.posix.SIGTERM
import platform.posix._exit
import platform.posix.chmod
import platform.posix.getenv
import platform.posix.signal
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.stderr
import kotlin.system.exitProcess
import kotlin.time.Clock

/**
 * The entry point of the session image: `ostrom-runner` is the runner (its container holds the runner token),
 * `ostrom-runner agent-host` the agent's side (its own container and uid, without the token, S2-49), and
 * `ostrom-runner check-host` the side that runs the hooks' checks for the runner (its own container, without the
 * token, the workspace mounted, its socket in a directory the agent's container does not mount). `ostrom-runner
 * machine` is a registered runner on a machine of the organization: it signs of life with its key until the server
 * refuses it.
 */
fun main(args: Array<String>) {
    val environment = readEnvironment()
    if (args.firstOrNull() == "machine") machine(environment)
    val socket = environment["OSTROM_AGENT_SOCKET"]?.takeIf { it.isNotBlank() } ?: AgentSocket.DEFAULT_PATH
    if (args.firstOrNull() == "agent-host") host(socket, AgentHost(PtyTerminal))
    if (args.firstOrNull() == "check-host") {
        val checkSocket = environment["OSTROM_CHECK_HOST_SOCKET"]?.takeIf { it.isNotBlank() }
            ?: RunnerConfig.DEFAULT_CHECK_HOST_SOCKET
        host(checkSocket, AgentHost(PtyTerminal.Pipes))
    }
    val config = try {
        RunnerConfig.from(environment) { path ->
            SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        }
    } catch (error: IllegalArgumentException) {
        log("invalid configuration: ${error.message}")
        exitProcess(EXIT_BAD_CONFIGURATION)
    }
    log("starting $config")
    val code = runBlocking {
        val client = HttpClient(CIO) {
            expectSuccess = false
            install(WebSockets)
            install(HttpTimeout) { connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS }
        }
        try {
            val selector = SelectorManager(Dispatchers.IO)
            val terminals = RemoteTerminalStarter({ AgentSocket.connect(selector, socket) })
            val server = HttpServerApi(client, config, this)
            val checkHost = RemoteTerminalStarter(
                { AgentSocket.connect(selector, config.checkHostSocket) },
                attempts = CHECK_HOST_ATTEMPTS,
            )
            val checks = RunnerChecks(checkHost, server, config.workspace, environment, Clock.System)
            // The agent's uid reaches the checks' socket: the directory is the agent socket's, mounted by both.
            val checksJob = launch {
                ChecksSocket.serve(selector, config.checksSocket, checks::run, ::log) { chmod(it, OPEN_MODE.convert()) }
            }
            try {
                Runner(config, server, terminals, environment, Clock.System, ::log).run()
            } finally {
                checksJob.cancel()
                selector.close()
            }
        } finally {
            client.close()
        }
    }
    log("exiting with $code")
    exitProcess(code)
}

/**
 * A registered runner (`ostrom-runner machine`): heartbeats with its key until the server refuses it (exit 3) or the
 * machine stops it (SIGTERM, exit 0).
 */
private fun machine(environment: Map<String, String>): Nothing {
    signal(SIGTERM, staticCFunction<Int, Unit> { _exit(0) })
    val config = try {
        MachineConfig.from(environment) { path ->
            SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        }
    } catch (error: IllegalArgumentException) {
        log("invalid configuration: ${error.message}")
        exitProcess(EXIT_BAD_CONFIGURATION)
    }
    log("starting $config")
    val code = runBlocking {
        val client = HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                requestTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            }
        }
        try {
            MachineRunner(HttpMachineApi(client, config), config.interval, ::log).run()
        } finally {
            client.close()
        }
    }
    log("exiting with $code")
    exitProcess(code)
}

/** A host (agent or checks): serves the runner's terminals on [socket], until the pod stops it (SIGTERM). */
private fun host(socket: String, host: AgentHost): Nothing {
    // As pid 1 of its container, the host gets no default action for SIGTERM: without this, a stop waits the grace.
    signal(SIGTERM, staticCFunction<Int, Unit> { _exit(0) })
    runBlocking {
        val selector = SelectorManager(Dispatchers.IO)
        // The runner, another uid, connects through the group both containers share (the pod's fsGroup).
        AgentSocket.serve(selector, socket, host, ::log) { chmod(it, SOCKET_MODE.convert()) }
    }
    exitProcess(0)
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
    "OSTROM_AGENT_COMMAND", "OSTROM_AGENT_MODEL", "OSTROM_AGENT_PROMPT", "OSTROM_AGENT_PROMPT_FILE",
    "OSTROM_REPOSITORY_URL", "OSTROM_REPOSITORY_REF", "OSTROM_RUNNER_TOKEN", "OSTROM_RUNNER_TOKEN_FILE",
    "OSTROM_WORKSPACE", "OSTROM_AGENT_SOCKET", "OSTROM_RUNNER_CHECKS", "OSTROM_CHECK_HOST_SOCKET",
    "OSTROM_RUNNER_ID", "OSTROM_RUNNER_KEY", "OSTROM_RUNNER_KEY_FILE", "OSTROM_RUNNER_INTERVAL_SECONDS",
)

private const val CONNECT_TIMEOUT_MILLIS = 10_000L

/** rw for the host's uid and the shared group, nothing for others (0660). */
private const val SOCKET_MODE = 0x1B0

/** rw for all (0666): the checks' socket, in a directory only the agent's and the runner's containers mount. */
private const val OPEN_MODE = 0x1B6

/** The check host starts with the session: a check waits for it a few seconds at most. */
private const val CHECK_HOST_ATTEMPTS = 20

/** The exit code of a bad configuration (1 is an execution the server ended). */
private const val EXIT_BAD_CONFIGURATION = 2
