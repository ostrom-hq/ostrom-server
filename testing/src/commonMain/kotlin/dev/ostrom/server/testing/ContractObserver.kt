// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

import dev.ostrom.server.kernel.config.ConfigSource
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
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

    /** The plugin that hands every answer to [observe]; a streamed one (no bytes in hand) is left aside. */
    val plugin: ApplicationPlugin<Unit> = createApplicationPlugin("ContractObserver") {
        on(ResponseBodyReadyForSend) { call, content ->
            val body = when (content) {
                is OutgoingContent.ByteArrayContent -> content.bytes()
                is OutgoingContent.NoContent -> ByteArray(0)
                else -> null
            }
            if (body != null) {
                val status = content.status ?: call.response.status() ?: HttpStatusCode.OK
                observe(call.request.httpMethod.value, call.request.path(), status.value, content.contentType, body)
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
            if (status in SUCCESS && operation.response(status)?.json != null && body.isNotEmpty()) {
                seen += operation.id
            }
        }
    }

    /**
     * Writes what this server saw to the reports, then fails on every drift [KnownDrifts] does not list.
     */
    suspend fun finish() {
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
        val declared = response?.json
        val parsed = if (json && body.isNotEmpty()) parsed(body) else null
        return when {
            response == null -> listOf(Violation("$", DriftKind.STATUS, "status $status is not declared"))
            declared == null && json && body.isNotEmpty() -> listOf(body("JSON where the contract declares none"))
            declared == null -> emptyList()
            !json || body.isEmpty() -> listOf(body("no JSON body where the contract declares one"))
            parsed == null -> listOf(body("a body that is not JSON"))
            else -> schema.violations(declared, parsed)
        }
    }

    private fun parsed(body: ByteArray) = try {
        Json.parseToJsonElement(body.decodeToString())
    } catch (_: SerializationException) {
        null
    }

    private fun body(detail: String) = Violation("$", DriftKind.BODY, detail)

}

private const val REPORTS = "build/reports/contract"
private const val INTERNAL = 500
private val UNFORESEEN = ContractResponse(JsonObject(mapOf("\$ref" to JsonPrimitive("#/components/schemas/ApiError"))))
private const val FIRST_SUCCESS = 200
private const val LAST_SUCCESS = 299
private val SUCCESS = FIRST_SUCCESS..LAST_SUCCESS

// One file per test process: the module's test tasks never write to the same one.
private val FILE = "observed-${Random.nextLong().toULong()}.txt"
