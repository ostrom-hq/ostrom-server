// SPDX-License-Identifier: AGPL-3.0-only
@file:OptIn(ExperimentalAtomicApi::class)

package run.ostrom.mcphost

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext

/**
 * Owns every process of the host: one per key, started on demand (an operation or a warm-up), shared by concurrent
 * operations, stopped when idle, too old, over its memory limit, revoked or replaced. Nothing is kept on disk beyond
 * the per-key caches, and nothing is shared between hosts: a restart only loses this cache. A port of TeamCode's
 * `Supervisor` (mcp/, docs/mcp.md).
 *
 * @param config The host's settings.
 * @param platform The machine.
 * @param context Where the supervision runs (its own [SupervisorJob] is added).
 * @param launchers Plans each start.
 */
@Suppress("TooManyFunctions") // The host protocol's operations and the supervision they share.
class Supervisor(
    private val config: HostConfig,
    private val platform: HostPlatform,
    context: CoroutineContext,
    private val launchers: Launchers = Launchers(config),
) : HostService {
    private val scope = CoroutineScope(context + SupervisorJob(context[kotlinx.coroutines.Job]))
    private val entriesLock = Mutex()
    private val entries = LinkedHashMap<String, Entry>()
    private val capacity = Mutex()
    private val activeOps = AtomicInt(0)

    @Volatile
    private var draining = false

    @Volatile
    private var closed = false

    private val launchersPresent: Boolean = config.launchers.all { launcher ->
        if (launcher == Launchers.NPM) executable(config.npx) && executable(NODE) else executable(config.uvx)
    } && (config.prlimit == null || executable(config.prlimit))

    private inner class Entry(val key: String) {
        /** When this key was first seen: a retain sweep spares it for [HostConfig.retainGraceMillis]. */
        val createdAt = platform.now()
        val mutex = Mutex()

        @Volatile var process: StdioProcess? = null

        @Volatile var starting: Deferred<StdioProcess>? = null

        @Volatile var fingerprint: String? = null

        @Volatile var launcher = ""

        @Volatile var pkg = ""

        // Changed under [mutex] only.
        val failureTimes = ArrayDeque<Long>()

        @Volatile var backoffUntil = 0L

        @Volatile var failures = 0

        @Volatile var starts = 0

        @Volatile var lastFailure: String? = null

        @Volatile var lastStartFailed = false

        @Volatile var lastExitCode: Int? = null

        @Volatile var coldStartMillis: Long? = null

        @Volatile var rssBytes: Long? = null

        @Volatile var lastStderr = ""

        fun state(now: Long = platform.now()) = when {
            backoffUntil > now -> ProcessStates.BACKOFF
            starting != null -> ProcessStates.STARTING
            process?.alive == true -> ProcessStates.READY
            lastStartFailed -> ProcessStates.FAILED
            else -> ProcessStates.IDLE
        }

        fun clearFailures() {
            backoffUntil = 0
            failureTimes.clear()
        }
    }

    init {
        scope.launch {
            var lastMemoryCheck = 0L
            while (true) {
                delay(config.tickMillis)
                // One failing check must not end idle stops, memory limits and stall detection for good.
                try {
                    val now = platform.now()
                    sweep(now)
                    if (now - lastMemoryCheck >= config.memoryCheckMillis) {
                        lastMemoryCheck = now
                        withContext(platform.io) { checkMemory() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    platform.events.emit(
                        buildJsonObject {
                            put("event", "tick_failed")
                            put("error", error::class.simpleName)
                        },
                    )
                }
            }
        }
    }

    private suspend fun entry(key: String): Entry = entriesLock.withLock { entries.getOrPut(key) { Entry(key) } }

    private suspend fun snapshot(): List<Entry> = entriesLock.withLock { entries.values.toList() }

    /**
     * One `list` or `call`, started or queued as needed, within [OpsRequest.deadlineMs].
     *
     * @param request The operation.
     * @throws HostFailure [HostErrors.BAD_REQUEST] for a malformed request.
     */
    override suspend fun op(request: OpsRequest): OpsResponse {
        validate(request.key)
        bad(request.op == OP_LIST || request.op == OP_CALL) { "op must be list or call." }
        // Null for a listing, the tool for a call.
        val tool = if (request.op == OP_LIST) null else request.tool?.takeIf { it.isNotEmpty() }
        bad(request.op == OP_LIST || tool != null) { "A call names its tool." }
        bad(request.deadlineMs in 1..MAX_DEADLINE) { "deadlineMs must be between 1 and 600000." }
        bad(request.maxResponseBytes in MIN_RESPONSE..MAX_RESPONSE) {
            "maxResponseBytes must be between 1 KiB and 64 MiB."
        }
        val entry = entry(request.key)
        activeOps.incrementAndFetch()
        try {
            var phase = Phase.START
            var cold = false
            val outcome = withTimeoutOrNull(request.deadlineMs) {
                try {
                    // obtain() counts this operation as a user, so the process is never stopped for idleness meanwhile.
                    val (process, wasCold) = obtain(entry, request.spec)
                    cold = wasCold
                    try {
                        phase = Phase.QUEUE
                        process.permits.withPermit {
                            phase = Phase.RUN
                            val result = if (tool == null) {
                                process.listTools(request.maxResponseBytes)
                            } else {
                                process.callTool(tool, request.arguments, request.maxResponseBytes)
                            }
                            Result.success(process.scrubber.scrub(result) as JsonObject)
                        }
                    } finally {
                        process.touch()
                        process.users.decrementAndFetch()
                    }
                } catch (failure: HostFailure) {
                    Result.failure(failure)
                }
            } ?: Result.failure(timedOut(phase))
            val failure = outcome.exceptionOrNull() as HostFailure?
            return OpsResponse(
                outcome.getOrNull(),
                failure?.let { HostError(it.code, it.message) },
                cold,
                entry.state(),
            )
        } finally {
            activeOps.decrementAndFetch()
        }
    }

    private fun timedOut(phase: Phase) = when (phase) {
        Phase.START -> HostFailure(
            HostErrors.TIMEOUT,
            "The MCP server did not finish starting in time; it keeps starting for the next call.",
        )
        Phase.QUEUE ->
            HostFailure(HostErrors.BUSY, "The MCP server is busy with ${config.maxInFlightPerProcess} calls.")
        Phase.RUN -> HostFailure(HostErrors.TIMEOUT, "The MCP server did not answer in time.")
    }

    private enum class Phase { START, QUEUE, RUN }

    /**
     * Starts the process of a key in the background, so the agent's first tools/list finds it ready.
     *
     * @param request The key and its definition.
     */
    override suspend fun warm(request: WarmRequest): KeyState {
        validate(request.key)
        if (closed) throw HostFailure(HostErrors.SHUTTING_DOWN, "The MCP host is shutting down.")
        val entry = entry(request.key)
        scope.launch {
            try {
                obtain(entry, request.spec).first.users.decrementAndFetch()
            } catch (_: HostFailure) {
                // Recorded on the key: its state and status say why.
            }
        }
        return KeyState(request.key, entry.state())
    }

    /**
     * Stops every process whose key is not listed (revoked assignments, deleted servers) and removes its directory. A
     * key first seen less than [HostConfig.retainGraceMillis] ago is spared: the relay read its keys just before, so a
     * process started for an assignment made since then must not be stopped.
     *
     * @param keys Every valid key.
     * @return How many processes it stops.
     */
    override suspend fun retain(keys: Collection<String>): Int {
        keys.forEach { validate(it) }
        val keep = keys.toSet()
        val stopping = mutableListOf<StdioProcess>()
        val now = platform.now()
        val revoked = entriesLock.withLock {
            entries.values.filter { it.key !in keep && now - it.createdAt >= config.retainGraceMillis }
                .onEach { entries.remove(it.key) }
        }
        for (entry in revoked) {
            entry.mutex.withLock {
                entry.process?.let {
                    retire(entry, it, StopReason.RETAINED)
                    stopping += it
                }
            }
        }
        scope.launch {
            stopping.forEach { withTimeoutOrNull(stopWait()) { it.exited.await() } }
            val known = entriesLock.withLock { entries.keys.toSet() }
            withContext(platform.io) {
                platform.files.list(config.dataDir)
                    .filter { HostProtocol.KEY.matches(it) && it !in keep && it !in known }
                    .forEach { platform.files.deleteRecursively("${config.dataDir}/$it") }
            }
        }
        return stopping.size
    }

    /**
     * An administrator's restart: stops the process and lifts a backoff. The next operation starts it again.
     *
     * @param key The process key.
     * @return Whether the key was known.
     */
    override suspend fun stop(key: String): Boolean {
        validate(key)
        val entry = entriesLock.withLock { entries[key] } ?: return false
        entry.mutex.withLock {
            entry.clearFailures()
            entry.lastStartFailed = false
            entry.process?.let { retire(entry, it, StopReason.STOPPED) }
        }
        return true
    }

    /** Every key's state, counters and scrubbed stderr. */
    override suspend fun status(): HostStatus = HostStatus(
        version = HOST_VERSION,
        protocol = HostProtocol.VERSION,
        launchers = config.launchers.sorted(),
        maxProcesses = config.maxProcesses,
        draining = draining,
        processes = snapshot().sortedBy { it.key }.map { entry ->
            val process = entry.process
            ProcessStatus(
                key = entry.key,
                state = entry.state(),
                launcher = entry.launcher,
                pkg = entry.pkg,
                pid = process?.pid,
                startedAt = process?.startedAt,
                lastUsedAt = process?.let { it.lastUsed.load() },
                inFlight = process?.let { it.users.load() } ?: 0,
                starts = entry.starts,
                failures = entry.failures,
                lastExitCode = entry.lastExitCode,
                lastFailure = entry.lastFailure,
                coldStartMillis = entry.coldStartMillis,
                rssBytes = entry.rssBytes,
                backoffUntil = entry.backoffUntil.takeIf { it > 0 },
                stderr = if (process == null) entry.lastStderr else process.stderr.snapshot(),
            )
        },
    )

    /** Ready while not draining, with the launchers' commands present. */
    override fun ready(): Boolean = !draining && launchersPresent

    /**
     * On SIGTERM: readiness fails so the Service stops sending new operations, what arrives meanwhile is still answered
     * for [HostConfig.drainMinMillis], and running operations get up to [HostConfig.drainMillis].
     */
    suspend fun drain() {
        draining = true
        val started = platform.now()
        delay(config.drainMinMillis)
        while (activeOps.load() > 0 && platform.now() - started < config.drainMillis) delay(POLL_MILLIS)
    }

    /** Stops every process and the supervision. Idempotent. */
    suspend fun shutdown() {
        if (closed) return
        closed = true
        draining = true
        val stopping = snapshot().mapNotNull { entry -> entry.process?.also { retire(entry, it, StopReason.SHUTDOWN) } }
        stopping.map { process ->
            scope.async { withTimeoutOrNull(stopWait() + SHUTDOWN_SLACK) { process.exited.await() } }
        }.awaitAll()
        scope.cancel()
    }

    private fun stopWait() = config.closeStdinWaitMillis + 2 * config.terminateWaitMillis

    /** The ready process of the key, or the one starting; counted as used until the caller decrements its users. */
    private suspend fun obtain(entry: Entry, spec: ProcessSpec): Pair<StdioProcess, Boolean> {
        val fingerprint = fingerprint(spec)
        val starting = entry.mutex.withLock {
            if (closed) throw HostFailure(HostErrors.SHUTTING_DOWN, "The MCP host is shutting down.")
            if (entry.fingerprint != fingerprint) {
                // Another definition for the same key: the relay changed something it should have put in the key. The
                // running process and a start in progress belong to the old definition and are not reused.
                entry.process?.let { retire(entry, it, StopReason.REPLACED) }
                entry.starting = null
                entry.clearFailures()
                entry.fingerprint = fingerprint
                entry.launcher = spec.launcher
                entry.pkg = spec.pkg
            }
            val now = platform.now()
            if (entry.backoffUntil > now) {
                throw HostFailure(
                    HostErrors.BACKOFF,
                    "The MCP server failed more than ${config.crashLimit} times in " +
                        "${config.crashWindowMillis / MINUTE} minutes; the host starts it again in " +
                        "${(entry.backoffUntil - now + MINUTE - 1) / MINUTE} minutes, or when an administrator " +
                        "restarts it.",
                )
            }
            entry.process?.takeIf { it.alive }?.let {
                it.users.incrementAndFetch()
                it.touch()
                return it to false
            }
            entry.starting ?: scope.async { start(entry, spec, fingerprint) }.also { entry.starting = it }
        }
        val process = starting.await()
        process.users.incrementAndFetch()
        process.touch()
        return process to true
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ThrowsCount") // TeamCode's start, every outcome in one place.
    private suspend fun start(entry: Entry, spec: ProcessSpec, fingerprint: String): StdioProcess {
        var launched: StdioProcess? = null
        try {
            reserve(entry)
            val dirs = ProcessDirs("${config.dataDir}/${entry.key}")
            val plan = launchers.plan(spec, dirs)
            val describe = buildJsonObject {
                put("key", entry.key.take(SHORT_KEY))
                put("launcher", spec.launcher)
                put("package", spec.pkg)
            }
            val process = StdioProcess(
                key = entry.key,
                plan = plan,
                dirs = dirs,
                config = config,
                platform = platform,
                scope = scope,
                describe = describe,
                onExit = { process, reason, code -> exited(entry, process, reason, code) },
                requestStop = { process, reason -> retire(entry, process, reason) },
            )
            val started = platform.now()
            try {
                withTimeout(config.startTimeoutMillis) {
                    process.launch()
                    // Launched: from here a cancellation kills it.
                    launched = process
                    process.initialize()
                }
            } catch (_: TimeoutCancellationException) {
                retireStarting(entry, process)
                throw HostFailure(
                    HostErrors.START_FAILED,
                    "The MCP server did not finish starting within ${config.startTimeoutMillis / SECOND} seconds.",
                )
            } catch (failure: HostFailure) {
                retireStarting(entry, process)
                if (failure.code != HostErrors.CRASHED) throw failure
                // A call fails as crashed once the exit is known: its code follows at once.
                val code = process.exited.await()
                throw HostFailure(HostErrors.START_FAILED, "The MCP server exited during startup (exit code $code).")
            }
            val elapsed = platform.now() - started
            entry.mutex.withLock {
                if (entry.fingerprint != fingerprint) {
                    // Replaced while it started: its callers asked for the old definition, which no longer runs.
                    retireStarting(entry, process, StopReason.REPLACED)
                    throw HostFailure(HostErrors.CRASHED, StopReason.REPLACED.message)
                }
                entry.starting = null
                if (closed || entriesLock.withLock { entries[entry.key] } !== entry) {
                    retireStarting(entry, process, StopReason.SHUTDOWN)
                    throw HostFailure(HostErrors.SHUTTING_DOWN, "The MCP server was revoked while it started.")
                }
                entry.process = process
                entry.starts++
                entry.coldStartMillis = elapsed
                entry.lastStartFailed = false
            }
            platform.events.emit(
                process.event("started") {
                    put("pid", process.pid)
                    put("coldStartMillis", elapsed)
                },
            )
            return process
        } catch (cancelled: CancellationException) {
            // The host is shutting down: no process may outlive it.
            launched?.kill()
            throw cancelled
        } catch (error: Exception) {
            // An unexpected error (a full disk, say) must not leave the key waiting on a failed start forever.
            val failure = error as? HostFailure
                ?: HostFailure(HostErrors.START_FAILED, "The MCP host could not start the server.")
            // Already retired when the failure came from the start itself: retiring twice only keeps its stderr again.
            launched?.let { retireStarting(entry, it) }
            entry.mutex.withLock {
                // A start replaced by a newer definition says nothing about the key's health.
                if (entry.fingerprint != fingerprint) return@withLock
                entry.starting = null
                if (failure.code != HostErrors.CAPACITY && failure.code != HostErrors.SHUTTING_DOWN) {
                    entry.lastStartFailed = true
                    recordFailure(entry, failure.code)
                }
            }
            platform.events.emit(
                buildJsonObject {
                    put("event", "start_failed")
                    put("key", entry.key.take(SHORT_KEY))
                    put("launcher", spec.launcher)
                    put("package", spec.pkg)
                    put("code", failure.code)
                },
            )
            throw failure
        }
    }

    private suspend fun retireStarting(
        entry: Entry,
        process: StdioProcess,
        reason: StopReason = StopReason.START_FAILED,
    ) {
        entry.lastStderr = process.stderr.snapshot()
        if (process.markStopping(reason)) scope.launch { process.stop(reason) }
    }

    /**
     * Makes room under [HostConfig.maxProcesses]: the idle process used the longest ago is stopped, never a busy one.
     */
    private suspend fun reserve(entry: Entry) = capacity.withLock {
        val live = snapshot().filter { it !== entry && (it.process != null || it.starting != null) }
        if (live.size < config.maxProcesses) return@withLock
        val (victim, process) = live.mapNotNull { other ->
            other.process?.takeIf { it.users.load() == 0 }?.let { other to it }
        }.minByOrNull { it.second.lastUsed.load() }
            ?: throw HostFailure(HostErrors.CAPACITY, "The MCP host already runs ${config.maxProcesses} busy servers.")
        retire(victim, process, StopReason.CAPACITY)
    }

    /** Detaches the process from its key and stops it in the background. False when it was already stopping. */
    private fun retire(entry: Entry, process: StdioProcess, reason: StopReason): Boolean {
        if (!process.markStopping(reason)) return false
        if (entry.process === process) entry.process = null
        scope.launch { process.stop(reason) }
        return true
    }

    private fun exited(entry: Entry, process: StdioProcess, reason: StopReason, code: Int) {
        scope.launch {
            entry.mutex.withLock {
                if (entry.process === process) entry.process = null
                entry.lastExitCode = code
                entry.lastStderr = process.stderr.snapshot()
                // A process that never became ready is counted once, by its failed start.
                if (process.ready && reason.failure) recordFailure(entry, reason.label)
            }
        }
    }

    /** Under the entry's lock. More than [HostConfig.crashLimit] failures in the window: backoff. */
    private fun recordFailure(entry: Entry, label: String) {
        val now = platform.now()
        entry.failures++
        entry.lastFailure = label
        entry.failureTimes.addLast(now)
        while (now - entry.failureTimes.first() > config.crashWindowMillis) entry.failureTimes.removeFirst()
        val recent = entry.failureTimes.size
        if (recent > config.crashLimit) {
            entry.backoffUntil = now + config.backoffMillis
            entry.failureTimes.clear()
            platform.events.emit(
                buildJsonObject {
                    put("event", "backoff")
                    put("key", entry.key.take(SHORT_KEY))
                    put("launcher", entry.launcher)
                    put("package", entry.pkg)
                    put("failures", recent)
                    put("untilSeconds", config.backoffMillis / SECOND)
                },
            )
        }
    }

    private suspend fun sweep(now: Long) {
        for (entry in snapshot()) {
            val process = entry.process ?: continue
            process.refreshTree()
            when {
                process.writeStalledMillis() > config.writeStallMillis -> retire(entry, process, StopReason.WRITE_STALL)
                process.users.load() > 0 -> Unit
                now - process.lastUsed.load() > config.idleMillis -> retire(entry, process, StopReason.IDLE)
                now - process.startedAt > config.maxLifetimeMillis -> retire(entry, process, StopReason.LIFETIME)
            }
        }
    }

    private suspend fun checkMemory() {
        for ((entry, process) in snapshot().mapNotNull { entry -> entry.process?.let { entry to it } }) {
            val rss = try {
                platform.table.rssBytes(process.pids())
            } catch (_: RuntimeException) {
                // A reading failure skips this round; a cancellation still stops the loop.
                currentCoroutineContext().ensureActive()
                null
            } ?: continue
            entry.rssBytes = rss
            if (rss > config.processMemoryBytes) retire(entry, process, StopReason.MEMORY)
        }
    }

    private fun validate(key: String) = bad(HostProtocol.KEY.matches(key)) {
        "A key is 64 lowercase hexadecimal characters."
    }

    private inline fun bad(condition: Boolean, message: () -> String) {
        if (!condition) throw HostFailure(HostErrors.BAD_REQUEST, message())
    }

    private fun executable(command: String): Boolean = platform.spawner.resolve(command, config.path) != null

    private fun fingerprint(spec: ProcessSpec): String {
        val canonical = HostProtocol.json.encodeToString(
            ProcessSpec.serializer(),
            spec.copy(env = spec.env.sortedByKey(), secrets = spec.secrets.sortedByKey()),
        )
        return CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(canonical.encodeToByteArray())
            .toHexString()
    }

    private fun <V> Map<String, V>.sortedByKey(): Map<String, V> = entries.sortedBy { it.key }.associate { it.toPair() }

    private companion object {
        const val NODE = "node"
        const val OP_LIST = "list"
        const val OP_CALL = "call"
        const val MAX_DEADLINE = 600_000L
        const val MIN_RESPONSE = 1_024
        const val MAX_RESPONSE = 64 * 1024 * 1024
        const val MINUTE = 60_000L
        const val SECOND = 1_000L
        const val SHORT_KEY = 12
        const val SHUTDOWN_SLACK = 1_000L
        const val POLL_MILLIS = 50L
    }
}
