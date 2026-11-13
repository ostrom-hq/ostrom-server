// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalAtomicApi::class)

package run.ostrom.mcphost

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement

/**
 * Why a process ends. A failure counts towards its key's backoff.
 *
 * @property label The name in events and in the status.
 * @property failure Whether it counts as a failure.
 * @property message What a call running at that moment answers.
 */
internal enum class StopReason(val label: String, val failure: Boolean, val message: String) {
    CRASHED("crashed", true, "The MCP server stopped during the call."),
    STDOUT_CLOSED("stdout_closed", true, "The MCP server closed its output during the call."),
    STDIN_CLOSED("stdin_closed", true, "The MCP server closed its input and was stopped."),
    MEMORY("memory", true, "The MCP server exceeded its memory limit and was stopped."),
    WRITE_STALL("write_stall", true, "The MCP server stopped reading its input and was stopped."),
    NON_JSON("non_json", true, "The MCP server wrote too much invalid output and was stopped."),
    OVERSIZE("oversize", true, "The MCP server wrote too many oversized messages and was stopped."),
    START_FAILED("start_failed", true, "The MCP server did not start."),
    IDLE("idle", false, "The MCP server was stopped after being idle."),
    LIFETIME("lifetime", false, "The MCP server reached its maximum lifetime and was stopped."),
    RETAINED("retained", false, "The MCP server is no longer assigned and was stopped."),
    STOPPED("stopped", false, "The MCP server was restarted by an administrator."),
    REPLACED("replaced", false, "The MCP server's definition changed and it was restarted."),
    CAPACITY("capacity", false, "The MCP server was stopped to make room for another one."),
    SHUTDOWN("shutdown", false, "The MCP host is shutting down."),
}

/**
 * One MCP server process and its stdio JSON-RPC session: one message per line in both directions, requests
 * multiplexed by ids the host assigns, server requests refused at once (-32601), stderr drained into a bounded buffer.
 * The process is a cache of the session: [initialize] runs once, and the tools/list answer is kept until the server
 * says it changed.
 *
 * @property key The process key.
 * @param plan What to run.
 * @param dirs The key's directories.
 * @param config The host's settings.
 * @param platform The machine.
 * @param scope The supervisor's scope: the process's own coroutines outlive the operation that started it.
 * @param describe The key's short form, launcher and package, for events.
 * @param onExit Called once, after the exit, with its reason and code.
 * @param requestStop Asks the supervisor to stop this process (invalid output, closed pipes).
 */
@Suppress("LongParameterList", "TooManyFunctions") // The process's collaborators; one session, one class.
internal class StdioProcess(
    val key: String,
    private val plan: LaunchPlan,
    private val dirs: ProcessDirs,
    private val config: HostConfig,
    private val platform: HostPlatform,
    private val scope: CoroutineScope,
    private val describe: JsonObject,
    private val onExit: (StdioProcess, StopReason, Int) -> Unit,
    private val requestStop: (StdioProcess, StopReason) -> Unit,
) {
    private class Reply(val message: JsonObject, val bytes: Int)

    /** Scrubs this process's secrets. */
    val scrubber: Scrubber = Scrubber(plan.secrets)

    /** Its last stderr. */
    val stderr: StderrBuffer = StderrBuffer(config.stderrBytes, scrubber)

    /** Its request slots. */
    val permits: Semaphore = Semaphore(config.maxInFlightPerProcess)

    /** Operations holding it: a process with users is never stopped for idleness or room. */
    val users: AtomicInt = AtomicInt(0)

    /** When it was created. */
    val startedAt: Long = platform.now()

    /** When it was last used. */
    val lastUsed: AtomicLong = AtomicLong(startedAt)

    /** Completed with the exit code once everything about the exit is done. */
    val exited: CompletableDeferred<Int> = CompletableDeferred()

    /** Whether `initialize` succeeded. */
    @Volatile
    var ready: Boolean = false
        private set

    private lateinit var child: ChildProcess
    private val childExit = CompletableDeferred<Int>()
    private val tools = AtomicReference<JsonObject?>(null)
    private val pendingLock = Mutex()
    private val pending = HashMap<Long, CompletableDeferred<Reply>>()
    private val nextId = AtomicLong(1)
    private val outbox = Channel<String>(config.maxInFlightPerProcess * 2 + OUTBOX_SLACK)
    private val writeStartedAt = AtomicLong(NOT_WRITING)
    private val stopReason = AtomicReference<StopReason?>(null)
    private val dead = AtomicBoolean(false)

    /** Every descendant seen so far: once the root has exited, its children can no longer be listed. */
    private val tree = AtomicReference<Set<Long>>(emptySet())
    private val nonJsonLines = ArrayDeque<Long>()
    private val oversizeLines = ArrayDeque<Long>()
    private lateinit var reader: Job
    private lateinit var errors: Job
    private lateinit var writer: Job

    /** The process id; read once started. */
    val pid: Long get() = child.pid

    /** Whether it runs and is not stopping (an exit sets its reason too; a failed launch never becomes a key's). */
    val alive: Boolean get() = stopReason.load() == null

    /** Marks it used now. */
    fun touch() = lastUsed.store(platform.now())

    /** The process and its live descendants. */
    fun pids(): List<Long> = listOf(child.pid) + platform.table.descendants(child.pid)

    /** Called on every supervisor tick: records the descendants while the root can still list them. */
    fun refreshTree() {
        val seen = tree.load() + platform.table.descendants(child.pid)
        tree.store(seen.filterTo(mutableSetOf()) { platform.table.alive(it) })
    }

    /** How long the current write to stdin has been blocked, 0 when none is. */
    fun writeStalledMillis(): Long = writeStartedAt.load().let { if (it == NOT_WRITING) 0 else platform.now() - it }

    /**
     * Claims the stop.
     *
     * @param reason Why.
     * @return False when it is already stopping for another reason.
     */
    fun markStopping(reason: StopReason): Boolean = stopReason.compareAndSet(null, reason)

    /** Starts the process. No shell: the argv goes to execve as [Launchers] built it. */
    suspend fun launch() {
        val program = withContext(platform.io) {
            dirs.all.forEach { platform.files.createDirectories(it) }
            platform.spawner.resolve(plan.command.first(), plan.environment.getValue("PATH"))
        }
        if (program == null) {
            markDead()
            throw HostFailure(HostErrors.START_FAILED, "${plan.command.first()} is not installed on this host.")
        }
        val process = try {
            platform.spawner.spawn(program, plan.command, plan.environment, plan.workDir)
        } catch (_: ChildIoException) {
            markDead()
            throw HostFailure(HostErrors.START_FAILED, "The launcher of the MCP server could not be started.")
        }
        child = process
        reader = scope.launch(platform.io) { readStdout(process) }
        errors = scope.launch(platform.io) { readStderr(process) }
        writer = scope.launch(platform.io) { writeStdin(process) }
        scope.launch(platform.io) { onProcessExit(process.awaitExit()) }
    }

    private fun markDead() {
        dead.store(true)
        exited.complete(-1)
    }

    /** `initialize`, then `notifications/initialized`. A server without the tools capability cannot be relayed. */
    suspend fun initialize() {
        val response = request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", PROTOCOL_VERSIONS.first())
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "ostrom-mcp-host")
                    put("version", HOST_VERSION)
                }
            },
            config.maxLineBytes,
        )
        error(response)?.let { (code, _) ->
            throw HostFailure(HostErrors.START_FAILED, "The MCP server refused initialize (JSON-RPC error $code).")
        }
        val result = response["result"] as? JsonObject
            ?: throw HostFailure(HostErrors.START_FAILED, "The MCP server answered initialize without a result.")
        expect(result.text("protocolVersion") in PROTOCOL_VERSIONS, HostErrors.START_FAILED) {
            "The MCP server does not support a protocol version the host speaks (${PROTOCOL_VERSIONS.joinToString()})."
        }
        expect(result.obj("capabilities")?.get("tools") is JsonObject, HostErrors.START_FAILED) {
            "The MCP server does not offer tools, the only capability the relay serves."
        }
        notify("notifications/initialized", null)
        ready = true
    }

    /**
     * Every page of tools/list (at most [MAX_LIST_PAGES] pages and [MAX_TOOLS] tools), kept until list_changed.
     *
     * @param maxResponseBytes The largest page accepted.
     */
    suspend fun listTools(maxResponseBytes: Int): JsonObject {
        tools.load()?.let { return it }
        val collected = mutableListOf<JsonElement>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = cursor?.let { buildJsonObject { put("cursor", it) } }
            val response = request("tools/list", params, maxResponseBytes)
            error(response)?.let { (code, _) ->
                throw HostFailure(HostErrors.PROTOCOL, "The MCP server answered tools/list with JSON-RPC error $code.")
            }
            val result = response.obj("result") ?: JsonObject(emptyMap())
            val entries = result["tools"] as? JsonArray
                ?: throw HostFailure(HostErrors.PROTOCOL, "The MCP server sent an invalid tools/list answer.")
            collected += entries.take(MAX_TOOLS - collected.size)
            cursor = result.text("nextCursor")
            pages++
        } while (cursor != null && collected.size < MAX_TOOLS && pages < MAX_LIST_PAGES)
        return JsonObject(mapOf("tools" to JsonArray(collected))).also { tools.store(it) }
    }

    /**
     * One tools/call.
     *
     * @param tool The tool.
     * @param arguments Its arguments.
     * @param maxResponseBytes The largest answer accepted.
     */
    suspend fun callTool(tool: String, arguments: JsonObject?, maxResponseBytes: Int): JsonObject {
        val params = buildJsonObject {
            put("name", tool)
            arguments?.let { put("arguments", it) }
        }
        val response = request("tools/call", params, maxResponseBytes)
        error(response)?.let { (code, message) ->
            throw HostFailure(
                HostErrors.TOOL_ERROR,
                "The MCP server returned error $code: ${scrubber.scrub(message).take(MAX_ERROR)}",
            )
        }
        return response["result"] as? JsonObject
            ?: throw HostFailure(HostErrors.PROTOCOL, "The MCP server sent an invalid tools/call answer.")
    }

    /**
     * Sends one request and waits for its answer. A cancellation (deadline, closed connection) sends
     * `notifications/cancelled` to the process, which keeps running.
     */
    private suspend fun request(method: String, params: JsonObject?, maxResponseBytes: Int): JsonObject {
        val id = nextId.fetchAndIncrement()
        val reply = CompletableDeferred<Reply>()
        pendingLock.withLock { pending[id] = reply }
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            params?.let { put("params", it) }
        }
        val refused = when {
            dead.load() -> HostFailure(HostErrors.CRASHED, StopReason.CRASHED.message)
            !outbox.trySend(message.toString()).isSuccess ->
                HostFailure(HostErrors.BUSY, "The MCP server's input queue is full.")
            else -> null
        }
        if (refused != null) {
            forget(id)
            throw refused
        }
        val answer = try {
            reply.await()
        } catch (cancelled: CancellationException) {
            // Also when the answer came just before: a server ignores a cancellation of what it already answered.
            withContext(NonCancellable) { forget(id) }
            // The specification forbids cancelling initialize: a start that gives up stops the process instead.
            if (method != "initialize") {
                notify(
                    "notifications/cancelled",
                    buildJsonObject {
                        put("requestId", id)
                        put("reason", "Cancelled by Ostrom.")
                    },
                )
            }
            throw cancelled
        }
        expect(answer.bytes <= maxResponseBytes, HostErrors.TOO_LARGE) {
            "The MCP server's answer exceeds the relay limit."
        }
        return answer.message
    }

    private inline fun expect(condition: Boolean, code: String, message: () -> String) {
        if (!condition) throw HostFailure(code, message())
    }

    private suspend fun forget(id: Long) = pendingLock.withLock { pending.remove(id) }

    // Best effort: a full queue means the process is not reading, which the stall check handles.
    private fun notify(method: String, params: JsonObject?) {
        outbox.trySend(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                params?.let { put("params", it) }
            }.toString(),
        )
    }

    private fun error(response: JsonObject): Pair<Long, String>? {
        val error = response.obj("error") ?: return null
        return (error.number("code") ?: 0L) to (error.text("message") ?: "")
    }

    private suspend fun readStdout(process: ChildProcess) {
        val lines = LineReader(config.maxLineBytes)
        while (true) {
            val chunk = process.readStdout() ?: break
            for (line in lines.feed(chunk)) {
                when (line) {
                    is LineReader.Line.Whole -> handle(line.bytes)
                    is LineReader.Line.Oversize -> oversize(line.head, line.tail)
                }
            }
        }
        // The output closed while the process lives on: it can no longer answer.
        if (withTimeoutOrNull(STDOUT_EXIT_WAIT) { childExit.await() } == null) {
            requestStop(this, StopReason.STDOUT_CLOSED)
        }
    }

    private suspend fun handle(bytes: ByteArray) {
        val text = bytes.decodeToString().trimEnd('\r')
        if (text.isBlank()) return
        val message = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        if (message == null) {
            // Many servers print a banner on stdout although the specification forbids it: ignored, and counted.
            if (count(nonJsonLines, MINUTE, config.nonJsonLimitPerMinute)) requestStop(this, StopReason.NON_JSON)
            return
        }
        val method = message.text("method")
        val id = message["id"]?.takeIf { it !is JsonNull }
        when {
            method != null && id != null -> answerServerRequest(method, id)
            method == "notifications/tools/list_changed" -> tools.store(null)
            method == "notifications/message" || method == "notifications/progress" ->
                stderr.append("[$method] ${message["params"] ?: JsonNull}\n")
            method != null -> Unit
            else -> message.number("id")?.let { forget(it) }?.complete(Reply(message, bytes.size))
        }
    }

    // sampling, elicitation, roots and any other request: the host announced no capability, so it never leaves one
    // waiting. A ping is answered.
    private fun answerServerRequest(method: String, id: JsonElement) {
        outbox.trySend(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                if (method == "ping") {
                    putJsonObject("result") {}
                } else {
                    putJsonObject("error") {
                        put("code", METHOD_NOT_FOUND)
                        put("message", "The Ostrom MCP host does not support server requests.")
                    }
                }
            }.toString(),
        )
    }

    private suspend fun oversize(head: ByteArray, tail: ByteArray) {
        platform.events.emit(event("oversize"))
        // The line was not parsed, but an answer's id sits at its start or its end: that call fails now.
        val id = idIn(head, headId) ?: idIn(tail, tailId)
        id?.let { forget(it) }?.completeExceptionally(
            HostFailure(
                HostErrors.TOO_LARGE,
                "The MCP server's answer exceeds the host's limit of ${config.maxLineBytes} bytes.",
            ),
        )
        if (count(oversizeLines, config.oversizeWindowMillis, config.oversizeLimit - 1)) {
            requestStop(this, StopReason.OVERSIZE)
        }
    }

    private fun idIn(bytes: ByteArray, pattern: Regex): Long? =
        pattern.find(bytes.decodeToString())?.let { it.groupValues[1].toLongOrNull() }

    /** Records one occurrence; true when more than [limit] happened within [windowMillis]. Reader coroutine only. */
    private fun count(times: ArrayDeque<Long>, windowMillis: Long, limit: Int): Boolean {
        val now = platform.now()
        times.addLast(now)
        while (now - times.first() > windowMillis) times.removeFirst()
        return times.size > limit
    }

    private suspend fun writeStdin(process: ChildProcess) {
        try {
            while (true) {
                val message = outbox.receive()
                writeStartedAt.store(platform.now())
                process.write((message + "\n").encodeToByteArray())
                writeStartedAt.store(NOT_WRITING)
            }
        } catch (_: ChildIoException) {
            // A broken stdin while the process lives on: nothing written will be read, so it cannot answer any more.
            requestStop(this, StopReason.STDIN_CLOSED)
        } finally {
            writeStartedAt.store(NOT_WRITING)
            process.closeStdin()
        }
    }

    private suspend fun readStderr(process: ChildProcess) {
        while (true) stderr.append((process.readStderr() ?: break).decodeToString())
    }

    private suspend fun onProcessExit(code: Int) {
        childExit.complete(code)
        // Answers and diagnostics written just before the exit are still read.
        withTimeoutOrNull(STDOUT_EXIT_WAIT) { listOf(reader, errors).joinAll() }
        val reason = stopReason.compareAndExchange(null, StopReason.CRASHED) ?: StopReason.CRASHED
        // npx or uvx may exit and leave the server they started: nothing of the tree outlives its root.
        val leftovers = tree.load().filter { platform.table.alive(it) }
        if (leftovers.isNotEmpty()) {
            leftovers.forEach { platform.table.signal(it, force = false) }
            scope.launch(platform.io) {
                delay(config.terminateWaitMillis)
                leftovers.filter { platform.table.alive(it) }.forEach { platform.table.signal(it, force = true) }
            }
        }
        dead.store(true)
        val failed = pendingLock.withLock { pending.values.toList().also { pending.clear() } }
        failed.forEach { it.completeExceptionally(HostFailure(HostErrors.CRASHED, reason.message)) }
        writer.cancel()
        exited.complete(code)
        platform.events.emit(
            event("exited") {
                put("code", code)
                put("reason", reason.label)
            },
        )
        onExit(this, reason, code)
    }

    /**
     * Ends the process. A graceful stop closes stdin and waits; a failure, or a process still running, gets SIGTERM on
     * its whole tree, then SIGKILL.
     *
     * @param reason Why.
     */
    suspend fun stop(reason: StopReason) {
        markStopping(reason)
        if (!::child.isInitialized) return
        val process = child
        refreshTree()
        val graceful = !reason.failure && run {
            writer.cancel()
            withTimeoutOrNull(config.closeStdinWaitMillis) { exited.await() } != null
        }
        refreshTree()
        val leftovers = tree.load().filter { platform.table.alive(it) }
        if (graceful && leftovers.isEmpty()) return
        // A graceful stop that got here has exited: only its descendants are left.
        if (!graceful) platform.events.emit(event("killed") { put("reason", reason.label) })
        process.signalGroup(force = false)
        leftovers.forEach { platform.table.signal(it, force = false) }
        val deadline = platform.now() + config.terminateWaitMillis
        while (platform.now() < deadline && (process.isAlive || leftovers.any { platform.table.alive(it) })) {
            delay(POLL_MILLIS)
        }
        if (process.isAlive) process.signalGroup(force = true)
        leftovers.filter { platform.table.alive(it) }.forEach { platform.table.signal(it, force = true) }
        writer.cancel()
        withTimeoutOrNull(config.terminateWaitMillis) { exited.await() }
    }

    /** Immediate SIGKILL of a launched process and its descendants, when the host cannot wait (its cancellation). */
    fun kill() {
        markStopping(StopReason.SHUTDOWN)
        child.signalGroup(force = true)
        tree.load().forEach { platform.table.signal(it, force = true) }
    }

    /**
     * One of the host's events, with the key's short form, the launcher and the package.
     *
     * @param name The event.
     * @param extra Its other fields.
     */
    fun event(name: String, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        put("event", name)
        describe.forEach { (key, value) -> put(key, value) }
        extra()
    }

    /** The protocol and the limits. */
    companion object {
        /** Newest first, as the relay's HTTP upstream. */
        private val PROTOCOL_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26")

        /** The most tools/list pages read. */
        const val MAX_LIST_PAGES: Int = 16

        /** The most tools kept. */
        const val MAX_TOOLS: Int = 256

        private const val METHOD_NOT_FOUND = -32601
        private const val OUTBOX_SLACK = 32
        private const val MAX_ERROR = 1_000
        private const val MINUTE = 60_000L
        private const val STDOUT_EXIT_WAIT = 1_000L
        private const val POLL_MILLIS = 50L
        private const val NOT_WRITING = -1L
        private val headId = Regex("""^\s*\{\s*(?:"jsonrpc"\s*:\s*"2\.0"\s*,\s*)?"id"\s*:\s*(\d+)\s*[,}]""")
        private val tailId = Regex("""[,{]\s*"id"\s*:\s*(\d+)\s*}\s*$""")
    }
}
