// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import dev.ostrom.server.kernel.config.ConfigSource
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.fail

/**
 * The ostrom-core contract a test run checks the server's answers against.
 *
 * @property schema Its schemas.
 * @property operations Its operations.
 */
class Contract(val schema: ContractSchema, val operations: ContractOperations) {
    /** Where the contract comes from. */
    companion object {
        // Each contract file read, once per test process.
        private val loaded = mutableMapOf<String, Contract>()

        /**
         * The contract CI gives (`OSTROM_CONTRACT_OPENAPI`, ostrom-core main's `api/openapi.yaml` as JSON), read once
         * per test process; null where none is given (a local run, the native job), unless `OSTROM_CONTRACT_REQUIRED`
         * is `true`, where a missing contract fails instead of skipping.
         *
         * @param environment The test's configuration.
         * @return The contract, or null.
         */
        fun from(environment: ConfigSource): Contract? {
            val path = environment["OSTROM_CONTRACT_OPENAPI"]
            check(path != null || environment["OSTROM_CONTRACT_REQUIRED"] != "true") {
                "OSTROM_CONTRACT_REQUIRED is true but OSTROM_CONTRACT_OPENAPI is not set."
            }
            return path?.let { file -> loaded.getOrPut(file) { read(file) } }
        }

        /**
         * The contract in a file.
         *
         * @param file `api/openapi.yaml` as JSON.
         * @return The contract.
         */
        fun read(file: String): Contract {
            val document = SystemFileSystem.source(Path(file)).buffered().use { it.readString() }
                .let { Json.parseToJsonElement(it).jsonObject }
            val schema = ContractSchema(document)
            return Contract(schema, ContractOperations(schema, document))
        }
    }
}

/**
 * A difference between an answer of the server and the contract.
 *
 * @property operation The operation's id.
 * @property status The answer's status.
 * @property violation What differs.
 */
data class Drift(val operation: String, val status: Int, val violation: Violation) {
    /** The same drift whatever the item of a list, as [KnownDrifts] lists it: `getGateCatalog 200 $.x MISSING`. */
    val key: String get() = "$operation $status ${violation.key}"

    override fun toString(): String = "$operation $status $violation"
}

/**
 * Watches every answer of a test server and checks each JSON answer of a `served` operation against the contract
 * (statuses, required and undeclared properties, types). [finish] writes what it saw for the coverage check
 * (`scripts/check-contract-coverage.py`) and fails on a drift [KnownDrifts] does not list. Without a contract it
 * watches nothing.
 *
 * @param contract The contract, or null.
 * @param known The drifts already known, each waiting for its fix.
 * @param reports Where [finish] appends what it saw, one file per test process.
 */
class ContractObserver(
    private val contract: Contract?,
    private val known: Set<String> = KnownDrifts.drifts.keys,
    private val reports: String = REPORTS,
) {
    private val lock = Mutex()
    private val seen = mutableSetOf<String>()
    private val drifts = mutableListOf<Drift>()

    // The copies of streamed answers, each one observed once its stream ends; [finish] waits for them.
    private val streams = SupervisorJob()

    /**
     * The plugin that hands every answer to [observe]. A streamed one (a channel, or a writer) that the contract
     * checks ([ContractOperation.reads]) is copied as it goes to the client and observed once it ends: the JSON the
     * gateway relays, the server-sent events of an inference.
     */
    val plugin: ApplicationPlugin<Unit> = createApplicationPlugin("ContractObserver") {
        on(ResponseBodyReadyForSend) { call, content ->
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val status = (content.status ?: call.response.status() ?: HttpStatusCode.OK).value
            val body = when (content) {
                is OutgoingContent.ByteArrayContent -> content.bytes()
                is OutgoingContent.NoContent -> ByteArray(0)
                else -> null
            }
            val streamed = content is OutgoingContent.ReadChannelContent ||
                content is OutgoingContent.WriteChannelContent
            when {
                body != null -> observe(method, path, status, content.contentType, body)
                streamed && reads(method, path, status) -> transformBodyTo(
                    Copied(content, CoroutineScope(streams)) { observe(method, path, status, content.contentType, it) },
                )
            }
        }
    }

    /**
     * Checks one answer.
     *
     * @param method The request's method.
     * @param path The request's path.
     * @param status The answer's status.
     * @param type The answer's content type.
     * @param body The answer's bytes, empty when none.
     */
    suspend fun observe(method: String, path: String, status: Int, type: ContentType?, body: ByteArray) {
        val contract = contract ?: return
        val operation = contract.operations.match(method, path)?.takeIf { it.served } ?: return
        val found = violations(contract.schema, operation, status, type, body)
        lock.withLock {
            drifts += found.map { Drift(operation.id, status, it) }
            val success = HttpStatusCode.fromValue(status).isSuccess()
            if (success && operation.response(status)?.json != null && body.isNotEmpty()) {
                seen += operation.id
            }
        }
    }

    /**
     * Writes what this server saw to the reports, then fails on every drift [KnownDrifts] does not list.
     */
    suspend fun finish() {
        withTimeoutOrNull(STREAMS_MILLIS) { streams.children.toList().joinAll() }
        streams.children.forEach { it.cancel() }
        val (operations, found) = lock.withLock { seen.toSet() to drifts.toList() }
        if (contract != null) {
            val lines = operations.map { "seen $it" } + found.map { "drift ${it.key} | $it" }
            SystemFileSystem.createDirectories(Path(reports))
            SystemFileSystem.sink(Path(reports, FILE), append = true).buffered()
                .use { sink -> lines.forEach { sink.writeString("$it\n") } }
        }
        val unknown = found.filter { it.key !in known }.distinctBy { it.key }
        if (unknown.isNotEmpty()) {
            fail(
                "The server's answers drift from the ostrom-core contract (fix the server, or the contract by an " +
                    "additive change; list a drift waiting for its fix in KnownDrifts):\n" +
                    unknown.joinToString("\n") { "${it.key}  ($it)" },
            )
        }
    }

    private fun violations(
        schema: ContractSchema,
        operation: ContractOperation,
        status: Int,
        type: ContentType?,
        body: ByteArray,
    ): List<Violation> {
        // A 500 is the unforeseen (ENG-007): no operation declares it, its body is still the contract's ApiError.
        val response = operation.response(status) ?: UNFORESEEN.takeIf { status == INTERNAL }
        val json = type?.match(ContentType.Application.Json) == true
        val stream = type?.match(ContentType.Text.EventStream) == true
        val declared = response?.json
        val events = response?.events
        return when {
            response == null -> listOf(Violation("$", DriftKind.STATUS, "status $status is not declared"))
            stream && events != null -> eventViolations(schema, events, response.done, body)
            declared == null && json && body.isNotEmpty() -> listOf(body("JSON where the contract declares none"))
            declared == null -> emptyList()
            else -> declaredViolations(schema, declared, response.other, json, body)
        }
    }

    // The answer of a response that declares a JSON body; [other] when it declares another type too.
    private fun declaredViolations(
        schema: ContractSchema,
        declared: JsonObject,
        other: Boolean,
        json: Boolean,
        body: ByteArray,
    ): List<Violation> {
        val parsed = if (json && body.isNotEmpty()) parsed(body.decodeToString()) else null
        return when {
            !json && other -> emptyList()
            !json || body.isEmpty() -> listOf(body("no JSON body where the contract declares one"))
            parsed == null -> listOf(body("a body that is not JSON"))
            else -> schema.violations(declared, parsed)
        }
    }

    // Each `data:` of server-sent events against [events], but [done], which ends them; one event per `data:` line.
    private fun eventViolations(schema: ContractSchema, events: JsonObject, done: String?, body: ByteArray) =
        body.decodeToString().lines()
            .filter { it.startsWith(DATA) }
            .map { it.removePrefix(DATA).removePrefix(" ") }
            .filter { it != done }
            .flatMap { data -> parsed(data)?.let { schema.violations(events, it) } ?: listOf(body(NOT_JSON_EVENT)) }

    // Whether a streamed answer is checked: a served operation's, with a status it does not declare or that it reads.
    private fun reads(method: String, path: String, status: Int): Boolean =
        contract?.let { it.operations.match(method, path) }?.takeIf { it.served }?.reads(status) == true

    private fun parsed(text: String) = try {
        Json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    }

    private fun body(detail: String) = Violation("$", DriftKind.BODY, detail)
}

/**
 * A streamed answer passed on to the client as it comes, and copied: [observed] gets the whole copy once it ends.
 *
 * @param original The answer, a channel or a writer.
 * @param scope Where the copy runs, and the writer's when the answer is one.
 * @param observed What to do with the whole answer.
 */
private class Copied(
    private val original: OutgoingContent,
    private val scope: CoroutineScope,
    private val observed: suspend (ByteArray) -> Unit,
) : OutgoingContent.ReadChannelContent() {
    override val contentType: ContentType? get() = original.contentType
    override val contentLength: Long? get() = original.contentLength
    override val status: HttpStatusCode? get() = original.status
    override val headers: Headers get() = original.headers

    override fun readFrom(): ByteReadChannel {
        val source = when (val content = original) {
            is OutgoingContent.ReadChannelContent -> content.readFrom()
            else -> scope.writer { (content as OutgoingContent.WriteChannelContent).writeTo(channel) }.channel
        }
        return scope.writer {
            val copy = Buffer()
            val chunk = ByteArray(CHUNK)
            while (true) {
                val read = source.readAvailable(chunk, 0, chunk.size)
                if (read == -1) break
                channel.writeFully(chunk, 0, read)
                channel.flush()
                copy.write(chunk, 0, read)
            }
            observed(copy.readByteArray())
        }.channel
    }
}

private const val REPORTS = "build/reports/contract"
private const val INTERNAL = 500
private const val DATA = "data:"
private const val NOT_JSON_EVENT = "a data: that is not JSON"
private const val CHUNK = 8192

// How long [ContractObserver.finish] waits for the streamed answers still being copied (a client that stopped
// reading one leaves it unfinished).
private const val STREAMS_MILLIS = 10_000L
private val UNFORESEEN = ContractResponse(JsonObject(mapOf("\$ref" to JsonPrimitive("#/components/schemas/ApiError"))))

// One file per test process: the module's test tasks never write to the same one.
private val FILE = "observed-${Random.nextLong().toULong()}.txt"
