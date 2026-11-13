// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalForeignApi::class)

package run.ostrom.runner

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.getuid
import platform.posix.lstat
import platform.posix.stat
import run.ostrom.runner.pty.ostrom_check_host_harden
import run.ostrom.runner.pty.ostrom_check_host_sweep
import kotlinx.cinterop.staticCFunction
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.network.selector.SelectorManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.posix.SIGTERM
import platform.posix._exit
import platform.posix.chmod
import platform.posix.getenv
import platform.posix.free
import platform.posix.mkdtemp
import platform.posix.realpath
import platform.posix.system
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
    if (args.firstOrNull() == "credential") credential(args.getOrNull(1), environment)
    val socket = environment["OSTROM_AGENT_SOCKET"]?.takeIf { it.isNotBlank() } ?: AgentSocket.DEFAULT_PATH
    if (args.firstOrNull() == "agent-host") host(socket, AgentHost(PtyTerminal))
    if (args.firstOrNull() == "check-host") {
        val checkSocket = environment["OSTROM_CHECK_HOST_SOCKET"]?.takeIf { it.isNotBlank() }
            ?: RunnerConfig.DEFAULT_CHECK_HOST_SOCKET
        checkHost(checkSocket, environment["OSTROM_WORKSPACE"] ?: RunnerConfig.DEFAULT_WORKSPACE)
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
            // The runner listens for the check host (R4): the side of the checks it trusts is its own uid.
            lateinit var link: CheckLink
            val listener = LinkListener(this, config.checkHostSocket) { ready ->
                AgentSocket.accept(selector, config.checkHostSocket, "check link", ::log, ready) { link.accept(it) }
            }
            link = CheckLink(before = listener::heal)
            listener.start()
            val checks = RunnerChecks(link, server, config.workspace, environment, Clock.System)
            // The agent's uid reaches the checks' socket: the directory is the agent socket's, mounted by both.
            val checksJob = launch {
                ChecksSocket.serve(selector, config.checksSocket, checks::run, ::log) { chmod(it, OPEN_MODE.convert()) }
            }
            val gitHubJob = gitHubAccess(selector, config, server)
            try {
                Runner(config, server, terminals, environment, Clock.System, ::log).run()
            } finally {
                gitHubJob.cancelAndJoin()
                checksJob.cancel()
                listener.stop()
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
 * git and `gh` reach GitHub with the owner's token, which only the runner holds and asks the server for: the
 * credential socket answers git's helper, and the `gh` configuration is written before the agent starts, then kept
 * current. Cancelling the job stops both and removes the configuration.
 */
private suspend fun CoroutineScope.gitHubAccess(
    selector: SelectorManager,
    config: RunnerConfig,
    server: HttpServerApi,
): Job {
    val gitHub = GitHubCredentialSource(server::gitHubToken, Clock.System)
    val gh = GitHubCliRenewal(gitHub, GitHubCliConfig(config.gitHubConfigDirectory), Clock.System, ::log)
    // The agent starts once the configuration is written, or after a bounded wait: a slow server delays no session.
    val first = withTimeoutOrNull(GITHUB_FIRST_TIMEOUT) { gh.refresh() } ?: GitHubCredentialSource.RETRY
    return launch {
        launch {
            CredentialSocket.serve(selector, config.credentialSocket, gitHub::credential, ::log) {
                chmod(it, OPEN_MODE.convert())
            }
        }
        gh.run(first)
    }
}

/**
 * The runner's listening socket for the check host, kept its own (R4). The directory is the check host's too: a script
 * may remove the socket and listen at its path. Before each check the runner makes sure the path is still the socket
 * it bound (its uid, its inode), and binds again otherwise; the check host's process behind the impostor is killed
 * after the check that started it.
 *
 * @param scope Where the listener runs.
 * @param path The socket's path.
 * @param listen Listens on [path], calling its argument once bound.
 */
private class LinkListener(
    private val scope: CoroutineScope,
    private val path: String,
    private val listen: suspend (ready: (String) -> Unit) -> Unit,
) {
    private var job: Job? = null
    private var bound: ULong? = null

    /** Starts listening; returns once the socket is bound. */
    suspend fun start() {
        val ready = CompletableDeferred<Unit>()
        job = scope.launch {
            listen { socket ->
                chmod(socket, OPEN_MODE.convert())
                bound = ownInode(socket)
                ready.complete(Unit)
            }
        }
        ready.await()
    }

    /** Binds again when the path is no longer the socket this runner bound. */
    suspend fun heal() {
        val current = ownInode(path)
        if (current != null && current == bound) return
        log("the check link socket was replaced: listening again")
        job?.cancelAndJoin()
        start()
    }

    /** Stops listening. */
    fun stop() {
        job?.cancel()
    }

    /** The inode at [socket] when it is a file of this process's uid, else null. */
    private fun ownInode(socket: String): ULong? = memScoped {
        val status = alloc<stat>()
        if (lstat(socket, status.ptr) != 0 || status.st_uid != getuid()) null else status.st_ino.convert()
    }
}

/**
 * The check host (R4): the pid 1 of its container, under the scripts' uid. It stops being dumpable, links to the
 * runner, then runs the checks the runner asks for one after the other, each in a new home, killing what each left
 * behind. When the link ends it exits: the runner accepts no second link, so later checks fail rather than reach
 * another process.
 */
private fun checkHost(socket: String, workspace: String): Nothing {
    signal(SIGTERM, staticCFunction<Int, Unit> { _exit(0) })
    if (ostrom_check_host_harden() != 0) log("the check host could not stop being dumpable")
    runBlocking {
        val selector = SelectorManager(Dispatchers.IO)
        val connect = suspend { AgentSocket.connect(selector, socket) }
        val link = linkToRunner(connect)
        log("check host linked to the runner")
        val terminals = CheckTerminals(PtyTerminal.Pipes, NativeCheckHomes, workspace, ::realPath)
        CheckHost(AgentHost(terminals), connect, ::log).serve(link)
    }
    log("the runner closed the check link")
    exitProcess(EXIT_LINK_CLOSED)
}

/** The first connection to the runner, retried while it starts. */
private suspend fun linkToRunner(connect: suspend () -> AgentConnection): AgentConnection {
    repeat(LINK_ATTEMPTS - 1) {
        try {
            return connect()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            delay(LINK_RETRY_MILLIS)
        }
    }
    return connect()
}

/** The real path of [path], links resolved, or null when it does not exist. */
private fun realPath(path: String): String? {
    val resolved = realpath(path, null) ?: return null
    return try {
        resolved.toKString()
    } finally {
        free(resolved)
    }
}

/** The checks' homes, under /tmp; a release kills every other process of the uid first (pid 1 only). */
private object NativeCheckHomes : CheckHomes {
    override fun create(): String = memScoped {
        val template = "/tmp/check-XXXXXX".cstr.getPointer(this)
        mkdtemp(template)?.toKString() ?: error("no home for the check")
    }

    override fun release(home: String) {
        if (ostrom_check_host_sweep() != 0) log("the check host could not kill what the check left behind")
        // The path is mkdtemp's: /tmp/check- and six letters or digits, nothing to quote.
        if (system("rm -rf -- $home") != 0) log("the check host could not remove a check's home")
    }
}

/**
 * A registered runner (`ostrom-runner machine`): heartbeats with its key until the server refuses it three times in a
 * row (exit 3) or the machine stops it (SIGTERM, exit 0). With a Docker Engine (`OSTROM_DOCKER_API_URL`), it runs the
 * cloud sessions the server hands it. A bad configuration, an unreadable key file included, exits with 2.
 */
private fun machine(environment: Map<String, String>): Nothing {
    signal(SIGTERM, staticCFunction<Int, Unit> { _exit(0) })
    val config = try {
        MachineConfig.from(environment) { path ->
            try {
                SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
            } catch (error: IOException) {
                throw IllegalArgumentException("OSTROM_RUNNER_KEY_FILE cannot be read (${error::class.simpleName}).")
            }
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
        // An image pull can take minutes: the engine's calls have their own, longer timeout.
        val engine = config.docker?.let {
            HttpClient(CIO) {
                expectSuccess = false
                install(HttpTimeout) {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    requestTimeoutMillis = DOCKER_TIMEOUT_MILLIS
                }
            }
        }
        try {
            val sessions = config.docker?.let { docker ->
                MachineSessions(
                    HttpDockerEngine(checkNotNull(engine), docker.apiUrl, config.runnerId),
                    PlanGuard(config.runnerId, docker.network, docker.image),
                    docker.maxSessions,
                    ::log,
                )
            }
            MachineRunner(HttpMachineApi(client, config), config.interval, ::log, sessions, MachineRunner.WAIT).run()
        } finally {
            client.close()
            engine?.close()
        }
    }
    log("exiting with $code")
    exitProcess(code)
}

/**
 * Git's credential helper (`ostrom-runner credential get`), in the agent's container: asks the runner's credential
 * socket for the remote git describes on stdin and prints what it answers. Every other operation, and every failure,
 * prints nothing and succeeds, so git moves on.
 */
private fun credential(operation: String?, environment: Map<String, String>): Nothing {
    if (operation == "get") {
        val socket = environment[RunnerConfig.CREDENTIAL_VARIABLE]?.takeIf { it.isNotBlank() }
            ?: RunnerConfig.DEFAULT_CREDENTIAL_SOCKET
        val request = CredentialHelper.parse(generateSequence { readlnOrNull() })
        val answer = runBlocking {
            val selector = SelectorManager(Dispatchers.IO)
            try {
                CredentialHelper.ask({ AgentSocket.connect(selector, socket) }, request)
            } finally {
                selector.close()
            }
        }
        print(CredentialHelper.format(answer))
    }
    exitProcess(0)
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
    "OSTROM_DOCKER_API_URL", "OSTROM_DOCKER_NETWORK", "OSTROM_RUNNER_SESSION_IMAGE", "OSTROM_CREDENTIAL_SOCKET",
    "OSTROM_GH_CONFIG_DIR",
)

private const val CONNECT_TIMEOUT_MILLIS = 10_000L

/** The longest the first GitHub token may take before the agent starts without it. */
private val GITHUB_FIRST_TIMEOUT = 10.seconds

/** An image pull, the longest call to the engine. */
private const val DOCKER_TIMEOUT_MILLIS = 600_000L

/** rw for the host's uid and the shared group, nothing for others (0660). */
private const val SOCKET_MODE = 0x1B0

/** rw for all (0666): the checks' socket, in a directory only the agent's and the runner's containers mount. */
private const val OPEN_MODE = 0x1B6

/** The check host links to the runner as the session starts: it tries for a minute. */
private const val LINK_ATTEMPTS = 120
private const val LINK_RETRY_MILLIS = 500L

/** The check host's exit once the runner closed the link. */
private const val EXIT_LINK_CLOSED = 3

/** The exit code of a bad configuration (1 is an execution the server ended). */
private const val EXIT_BAD_CONFIGURATION = 2
