// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.mcphost

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.CoroutineContext

/**
 * A stdio MCP server in Kotlin, the twin of the Node.js fixture of the JVM tests: its behaviour comes from its
 * environment (`FIXTURE_MODE`: banner, no-tools, exit-at-start, ignore-term, slow-start, old-protocol, no-result,
 * init-error, bad-list, list-error, stuck-start).
 */
class FakeChild(
    override val pid: Long,
    val argv: List<String>,
    val environment: Map<String, String>,
    private val scope: CoroutineScope,
    private val table: FakeTable,
) : ChildProcess {
    private val stdout = Channel<ByteArray>(Channel.UNLIMITED)
    private val stderr = Channel<ByteArray>(Channel.UNLIMITED)
    private val exit = CompletableDeferred<Int>()
    private val mode = environment["FIXTURE_MODE"].orEmpty()
    private val asks = HashMap<String, CompletableDeferred<JsonObject>>()
    private var nextAsk = 1
    private var added = false
    private var stdinClosed = false
    private var stuck = false
    private var initializeCount = 0
    val received = mutableListOf<JsonObject>()
    val cancelled = mutableListOf<Long>()

    override val isAlive: Boolean get() = !exit.isCompleted

    init {
        table.live += pid
        if ("exit-at-start" in mode) {
            exit(7)
        } else {
            if ("banner" in mode) stdout.trySend("fixture server starting\n".encodeToByteArray())
            stderr.trySend("fixture stderr secret=${environment["FIXTURE_SECRET"].orEmpty()}\n".encodeToByteArray())
        }
    }

    fun exit(code: Int) {
        if (exit.isCompleted) return
        table.live -= pid
        stdout.close()
        stderr.close()
        exit.complete(code)
    }

    fun emit(text: String) {
        stdout.trySend(text.encodeToByteArray())
    }

    override suspend fun readStdout(): ByteArray? = stdout.receiveCatching().getOrNull()

    override suspend fun readStderr(): ByteArray? = stderr.receiveCatching().getOrNull()

    override suspend fun write(bytes: ByteArray) {
        if (stdinClosed || exit.isCompleted) throw ChildIoException("closed")
        if (stuck) awaitCancellation()
        for (line in bytes.decodeToString().split('\n').filter { it.isNotBlank() }) {
            handle(Json.parseToJsonElement(line).jsonObject)
        }
    }

    override fun closeStdin() {
        stdinClosed = true
        if ("ignore-term" !in mode) exit(0)
    }

    override suspend fun awaitExit(): Int = exit.await()

    override fun signalGroup(force: Boolean) {
        if (force) exit(137) else if ("ignore-term" !in mode) exit(143)
    }

    private fun send(message: JsonObject) {
        stdout.trySend((message.toString() + "\n").encodeToByteArray())
    }

    private fun rpc(block: JsonObjectBuilder.() -> Unit) = send(
        buildJsonObject {
            put("jsonrpc", "2.0")
            block()
        },
    )

    private fun reply(id: JsonElement, result: JsonObject) = send(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        },
    )

    private fun error(id: JsonElement, code: Int, message: String) = send(
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        },
    )

    private fun text(value: String) = buildJsonObject {
        putJsonArray("content") {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", value)
                },
            )
        }
    }

    private fun tools(): List<String> {
        val count = environment["FIXTURE_TOOL_COUNT"]?.toInt()
        val names = if (count == null) NAMES else List(count) { "tool$it" }
        return if (added) names + "added" else names
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun handle(message: JsonObject) {
        received += message
        val method = (message["method"] as? JsonPrimitive)?.content
        val id = message["id"]
        if (method == null) {
            asks.remove(id?.jsonPrimitive?.content)?.complete(message)
            return
        }
        if (method == "notifications/cancelled") {
            cancelled += message["params"]!!.jsonObject["requestId"]!!.jsonPrimitive.long
            return
        }
        if (id == null) return
        val params = message["params"] as? JsonObject
        when (method) {
            "initialize" -> {
                initializeCount++
                if ("stuck-start" in mode) return
                if ("init-error" in mode) return error(id, -32000, "no")
                if ("no-result" in mode) return rpc { put("id", id) }
                val version =
                    if ("old-protocol" in mode) "2024-01-01" else params!!["protocolVersion"]!!.jsonPrimitive.content
                val answer = buildJsonObject {
                    put("protocolVersion", version)
                    if ("no-capabilities" !in mode) {
                        putJsonObject("capabilities") {
                            if ("no-tools" !in mode) putJsonObject("tools") { put("listChanged", true) }
                        }
                    }
                }
                if ("slow-start" in mode) {
                    scope.launch {
                        delay(environment["FIXTURE_START_DELAY"]?.toLong() ?: 1_000)
                        reply(id, answer)
                    }
                } else {
                    reply(id, answer)
                }
            }
            "tools/list" -> {
                if ("list-error" in mode) return error(id, -32000, "no")
                if ("bad-list" in mode) return reply(id, buildJsonObject { put("nope", 1) })
                if ("unanswered-list" in mode) return rpc { put("id", id) }
                val pageSize = environment["FIXTURE_PAGE_SIZE"]?.toInt() ?: 1_000
                val start = (params?.get("cursor") as? JsonPrimitive)?.content?.toInt() ?: 0
                val all = tools()
                reply(
                    id,
                    buildJsonObject {
                        putJsonArray("tools") {
                            all.drop(start).take(pageSize).forEach { add(buildJsonObject { put("name", it) }) }
                        }
                        val next = start + pageSize
                        if (next < all.size) {
                            if ("numeric-cursor" in mode) {
                                put("nextCursor", next)
                            } else {
                                put("nextCursor", next.toString())
                            }
                        }
                    },
                )
            }
            "tools/call" -> call(id, params!!)
            else -> error(id, -32601, "Method not found")
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun call(id: JsonElement, params: JsonObject) {
        val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        when (params["name"]!!.jsonPrimitive.content) {
            "echo" -> reply(id, text((args["text"] as? JsonPrimitive)?.content.orEmpty()))
            "slow" -> scope.launch {
                delay((args["ms"] as? JsonPrimitive)?.long ?: 1_000)
                reply(id, text("done"))
            }
            "crash" -> exit(3)
            "big" -> reply(id, text("x".repeat(args["bytes"]!!.jsonPrimitive.int)))
            "env" -> reply(id, text(JsonObject(environment.mapValues { JsonPrimitive(it.value) }).toString()))
            "secret" -> reply(id, text("the secret is ${environment["FIXTURE_SECRET"]}"))
            "fail" -> error(id, -32602, "bad arguments ${environment["FIXTURE_SECRET"]}")
            "bare-error" -> rpc { put("id", id); putJsonObject("error") {} }
            "bad" -> rpc { put("id", id); put("result", JsonArray(emptyList())) }
            "ask", "ping" -> {
                val askId = "ask-${nextAsk++}"
                val answer = CompletableDeferred<JsonObject>()
                asks[askId] = answer
                send(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", askId)
                        val ping = params["name"]!!.jsonPrimitive.content == "ping"
                        put("method", if (ping) "ping" else "sampling/createMessage")
                    },
                )
                scope.launch { reply(id, text(answer.await().toString())) }
            }
            "change" -> {
                added = true
                rpc { put("method", "notifications/tools/list_changed") }
                rpc { put("method", "notifications/message"); put("params", "logged") }
                rpc { put("method", "notifications/other") }
                rpc { put("id", JsonPrimitive("text-id")); put("result", JsonObject(emptyMap())) }
                rpc { put("id", 99_999); put("result", JsonObject(emptyMap())) }
                rpc { put("method", "notifications/progress"); put("id", null as String?) }
                rpc { put("method", 5) }
                emit("\n")
                emit("[1]\n")
                reply(id, text("changed"))
            }
            "spawn" -> {
                val child = pid * 100 + table.children.getOrPut(pid) { mutableListOf() }.size + 1
                table.children.getValue(pid) += child
                table.live += child
                reply(id, text(child.toString()))
            }
            "stats" -> reply(id, text("initialize=$initializeCount cancelled=$cancelled"))
            "stuck" -> {
                stuck = true
                reply(id, text("stuck"))
            }
            "spam" -> {
                repeat(args["lines"]!!.jsonPrimitive.int) { emit("noise $it\n") }
                reply(id, text("spammed"))
            }
            "close-stdin" -> {
                reply(id, text("closing"))
                stdinClosed = true
            }
            "close-stdout" -> stdout.close()
            "oversize" -> {
                val filler = "y".repeat(args["bytes"]!!.jsonPrimitive.int)
                when ((args["where"] as? JsonPrimitive)?.content) {
                    "tail" -> emit(
                        "{\"jsonrpc\":\"2.0\",\"result\":{\"x\":\"$filler\"},\"id\":${id.jsonPrimitive.content}}\n",
                    )
                    "none" -> emit("{\"jsonrpc\":\"2.0\",\"result\":\"$filler\"}\n")
                    "huge" -> emit("{\"id\":99999999999999999999,\"result\":\"$filler\"}\n")
                    "unknown" -> emit("{\"id\":424242,\"result\":\"$filler\"}\n")
                    else -> emit("{\"jsonrpc\":\"2.0\",\"id\":${id.jsonPrimitive.content},\"result\":\"$filler\"}\n")
                }
            }
            "stderr" -> {
                stderr.trySend((args["text"]!!.jsonPrimitive.content).encodeToByteArray())
                reply(id, text("written"))
            }
            else -> error(id, -32602, "Unknown tool")
        }
    }

    private companion object {
        val NAMES =
            listOf("echo", "slow", "crash", "big", "env", "secret", "fail", "ask", "ping", "change", "spawn", "stats")
    }
}

/** The process table of the fakes: descendants, liveness, signals and memory. */
class FakeTable : ProcessTable {
    val live = mutableSetOf<Long>()
    val children = mutableMapOf<Long, MutableList<Long>>()
    val signals = mutableListOf<Pair<Long, Boolean>>()
    val ignoringTerm = mutableSetOf<Long>()
    var memory: (List<Long>) -> Long? = { null }
    var failDescendants = false

    override fun descendants(pid: Long): List<Long> {
        check(!failDescendants) { "unreadable" }
        return children[pid].orEmpty().filter { it in live }
    }

    override fun alive(pid: Long): Boolean = pid in live

    override fun signal(pid: Long, force: Boolean) {
        signals += pid to force
        if (force || pid !in ignoringTerm) live -= pid
    }

    override fun rssBytes(pids: List<Long>): Long? = memory(pids)
}

/** Directories in memory. */
class FakeFiles : HostFiles {
    val directories = mutableSetOf<String>()
    val deleted = mutableListOf<String>()
    var failCreate = false
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun createDirectories(path: String) {
        gate?.await()
        check(!failCreate) { "disk full" }
        directories += path
    }

    override fun deleteRecursively(path: String) {
        deleted += path
        directories.removeAll { it == path || it.startsWith("$path/") }
    }

    override fun list(path: String): List<String> =
        directories.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct()
}

/** Starts [FakeChild]ren. */
class FakeSpawner(private val scope: CoroutineScope, private val table: FakeTable) : ProcessSpawner {
    val installed = mutableSetOf("npx", "node", "uvx", "prlimit")
    val started = mutableListOf<FakeChild>()
    var failSpawn = false
    private var nextPid = 1000L

    override fun resolve(command: String, path: String): String? = when {
        '/' in command -> command
        command in installed -> "/usr/bin/$command"
        else -> null
    }

    override fun spawn(
        program: String,
        argv: List<String>,
        environment: Map<String, String>,
        workDir: String,
    ): ChildProcess {
        if (failSpawn) throw ChildIoException("no")
        return FakeChild(nextPid++, listOf(program) + argv.drop(1), environment, scope, table).also { started += it }
    }
}

/** Every fake of one test, and a supervisor over them. */
class HostHarness(
    val scope: CoroutineScope,
    context: CoroutineContext,
    now: () -> Long,
    config: HostConfig = CONFIG,
    prepare: HostHarness.() -> Unit = {},
) {
    val table = FakeTable()
    val files = FakeFiles()
    val spawner = FakeSpawner(scope, table)
    val events = mutableListOf<JsonObject>()
    val platform = HostPlatform(spawner, table, files, { events += it }, context, now)
    val supervisor: Supervisor

    init {
        prepare()
        supervisor = Supervisor(config, platform, context)
    }

    fun last(): FakeChild = spawner.started.last()

    companion object {
        const val TOKEN = "0123456789abcdef0123456789abcdef"
        val CONFIG = HostConfig(token = TOKEN, dataDir = "/var/mcp", retainGraceMillis = 0)
        val KEY = "a".repeat(64)
        val OTHER = "b".repeat(64)
        const val SECRET = "s3cr3t-value"

        fun spec(mode: String = "", extra: Map<String, String> = emptyMap(), secret: String = SECRET) = ProcessSpec(
            launcher = "npm",
            pkg = "@acme/server@1.2.3",
            args = listOf("--token", "\${FIXTURE_SECRET}"),
            env = mapOf("FIXTURE_MODE" to mode) + extra,
            secrets = mapOf("FIXTURE_SECRET" to secret),
            declaredAt = 1_700_000_000_000,
        )

        @Suppress("LongParameterList")
        fun call(
            tool: String,
            key: String = KEY,
            spec: ProcessSpec = spec(),
            deadline: Long = 60_000,
            max: Int = 1 shl 20,
            arguments: JsonObject? = null,
        ) =
            OpsRequest(key, spec, "call", tool, arguments, deadline, max)

        fun list(key: String = KEY, spec: ProcessSpec = spec(), deadline: Long = 60_000, max: Int = 1 shl 20) =
            OpsRequest(key, spec, "list", null, null, deadline, max)
    }
}
