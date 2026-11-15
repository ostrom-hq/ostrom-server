// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.testing

import run.ostrom.server.kernel.http.docs.ApiDocs
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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.fail

/**
 * The contract a test server's answers are checked against: the server's own OpenAPI document, generated from its
 * routing tree and its modules' catalogues ([ApiDocs], ostrom-core docs/decisions/0001), the one `@ostrom-hq/openapi`
 * publishes.
 *
 * @property schema Its schemas.
 * @property operations Its operations.
 */
class Contract(val schema: ContractSchema, val operations: ContractOperations) {
    /** Where the contract comes from. */
    companion object {
        // Each document built, once per test process and set of routes (ApiDocs.signature).
        private val generated = mutableMapOf<String, Contract>()

        /**
         * The contract of a server: its generated document, built once per test process for each set of routes.
         *
         * @param docs The server's document.
         * @return The contract.
         */
        fun of(docs: ApiDocs): Contract = synchronized(generated) {
            generated.getOrPut(docs.signature()) { parse(docs.document()) }
        }

        /**
         * The contract an OpenAPI document describes.
         *
         * @param text The document, as JSON.
         * @return The contract.
         */
        fun parse(text: String): Contract {
            val document = Json.parseToJsonElement(text).jsonObject
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
    /** The same drift whatever the item of a list: `getGateCatalog 200 $.x MISSING`. */
    val key: String get() = "$operation $status ${violation.key}"

    override fun toString(): String = "$operation $status $violation"
}

/**
 * Watches every answer of a test server and checks each JSON answer of an operation of the server's generated document
 * against it (statuses, required and undeclared properties, types): the catalogue's `ok<T>()`, `body<T>()` and errors
 * must be what the routes really answer. [finish] fails on any drift. An operation the document leaves undescribed (no
 * responses) is not checked: ApiDocumentPostgresTest fails on it. Without a contract it watches nothing.
 *
 * @param contract The contract, read at the first answer (the document is built once the routes are mounted), or null.
 */
class ContractObserver(contract: () -> Contract?) {
    /**
     * An observer of a contract known in advance.
     *
     * @param contract The contract, or null.
     */
    constructor(contract: Contract?) : this({ contract })

    private val lock = Mutex()
    private val contract by lazy(contract)
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
        val operation = contract.operations.match(method, path)?.takeIf { it.responses.isNotEmpty() } ?: return
        val found = violations(contract.schema, operation, status, type, body)
        if (found.isNotEmpty()) lock.withLock { drifts += found.map { Drift(operation.id, status, it) } }
    }

    /** Fails on every drift met, one line per kind of drift. */
    suspend fun finish() {
        val unknown = lock.withLock { drifts.toList() }.distinctBy { it.key }
        if (unknown.isNotEmpty()) {
            fail(
                "The server's answers drift from its generated OpenAPI document (fix the route, or its entry in the " +
                    "module's presentation/<X>Operations.kt):\n" +
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
        // A 500 is the unforeseen (ENG-007): its body is the ApiError envelope even where no operation declares it.
        val response = operation.response(status) ?: UNFORESEEN.takeIf { status == INTERNAL }
        val json = type?.match(ContentType.Application.Json) == true
        val declared = response?.json
        return when {
            response == null -> listOf(Violation("$", DriftKind.STATUS, "status $status is not declared"))
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
        val parsed = if (json && body.isNotEmpty()) parsed(body) else null
        return when {
            !json && other -> emptyList()
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

private const val INTERNAL = 500
private val UNFORESEEN = ContractResponse(JsonObject(mapOf("\$ref" to JsonPrimitive("#/components/schemas/ApiError"))))
