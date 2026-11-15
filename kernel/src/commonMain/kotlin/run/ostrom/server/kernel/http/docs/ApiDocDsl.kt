// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.http.docs

import run.ostrom.server.kernel.http.ApiError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Operation
import io.ktor.openapi.ReferenceOr
import io.ktor.openapi.Response
import io.ktor.openapi.Responses
import io.ktor.openapi.jsonSchema

/**
 * The documentation of one operation, keyed as the routing tree formats its path (`GET /api/v1/artifacts/{id}`), as
 * TeamCode's `ApiDocDsl.kt` writes it.
 *
 * @property method The operation's method.
 * @property path The route's path, its parameters written `{name}`.
 * @property describe Its operation id, tag, summary, description, parameters, body and specific responses. The
 *   authentication and the errors every operation shares are added by [describeWith] and [commonResponses].
 * @property conditional Whether its module mounts the route only under some settings ([ApiCatalog.conditionally]).
 */
class ApiOperation(
    val method: HttpMethod,
    val path: String,
    val conditional: Boolean = false,
    val describe: Operation.Builder.() -> Unit,
) {
    /** `METHOD /path`, as the routing tree names the route. */
    val key: String get() = "${method.value} $path"
}

/**
 * The operations a module documents ([run.ostrom.server.kernel.module.ServerModule.apiOperations]): one entry per
 * route it mounts, and the tags its entries use.
 */
class ApiCatalog {
    /** The entries, in declaration order. */
    val operations: MutableList<ApiOperation> = mutableListOf()

    /** The tags' descriptions by name, in declaration order. */
    val tags: MutableMap<String, String> = linkedMapOf()

    private var conditional = false

    /**
     * Declares a tag of the document.
     *
     * @param name The tag, as the entries name it with `tag(name)`.
     * @param description What its operations are about.
     */
    fun tag(name: String, description: String) {
        tags[name] = description
    }

    /**
     * Documents, with [entries], routes the module mounts only under some settings (another module, a setting): a
     * server that does not mount one leaves it out of its document, and its coverage does not report it unrouted.
     *
     * @param entries The entries.
     */
    fun conditionally(entries: ApiCatalog.() -> Unit) {
        val outer = conditional
        conditional = true
        try {
            entries()
        } finally {
            conditional = outer
        }
    }

    /**
     * Documents `GET path`.
     *
     * @param path The route's path.
     * @param describe The operation.
     */
    fun get(path: String, describe: Operation.Builder.() -> Unit) {
        operations += ApiOperation(HttpMethod.Get, path, conditional, describe)
    }

    /**
     * Documents `POST path`.
     *
     * @param path The route's path.
     * @param describe The operation.
     */
    fun post(path: String, describe: Operation.Builder.() -> Unit) {
        operations += ApiOperation(HttpMethod.Post, path, conditional, describe)
    }

    /**
     * Documents `PUT path`.
     *
     * @param path The route's path.
     * @param describe The operation.
     */
    fun put(path: String, describe: Operation.Builder.() -> Unit) {
        operations += ApiOperation(HttpMethod.Put, path, conditional, describe)
    }

    /**
     * Documents `PATCH path`.
     *
     * @param path The route's path.
     * @param describe The operation.
     */
    fun patch(path: String, describe: Operation.Builder.() -> Unit) {
        operations += ApiOperation(HttpMethod.Patch, path, conditional, describe)
    }

    /**
     * Documents `DELETE path`.
     *
     * @param path The route's path.
     * @param describe The operation.
     */
    fun delete(path: String, describe: Operation.Builder.() -> Unit) {
        operations += ApiOperation(HttpMethod.Delete, path, conditional, describe)
    }
}

/**
 * Who may call an operation, read from the routing tree: a route inside `authenticated { }` needs a credential.
 *
 * @property public No credential needed.
 * @property signed An incoming webhook: no credential, the sender's signature is the proof.
 */
internal class ApiAccess(val public: Boolean, val signed: Boolean = false) {
    /** The sentence the operation's description ends with. */
    val text: String
        get() = when {
            !public -> "A bearer token (CLI, app, device, agent, runner or service key) or the console's " +
                "session cookie."
            signed -> "No credential: the sender signs the request, and an unsigned or wrongly signed one is refused."
            else -> "No authentication."
        }
}

/** The operation's own description followed by its authentication, and the session cookie's security requirement. */
internal fun ApiOperation.describeWith(access: ApiAccess): Operation.Builder.() -> Unit = {
    describe()
    description = listOfNotNull(description?.takeIf { it.isNotBlank() }, "**Authentication:** ${access.text}")
        .joinToString("\n\n")
    // Ktor adds the bearer token's requirement ([BEARER_SCHEME]) from `authenticated { }`; the cookie is the other way.
    if (!access.public) security { requirement(SESSION_SCHEME) }
}

/**
 * The errors every operation can answer: 400 for an invalid body, 401 and 403 for a protected operation, 500, and a
 * `default`, all in the [ApiError] envelope. Where the operation describes one of these statuses itself, its text
 * comes first and the shared case is kept after it.
 *
 * @param operation The entry.
 * @param access Who may call it.
 *
 * @return The responses to add to the route's description.
 */
internal fun commonResponses(operation: ApiOperation, access: ApiAccess): Operation.Builder.() -> Unit {
    val own = Operation.build(configure = operation.describe)
    // The catalogue's builders only write values, never references.
    val declared = own.responses?.responses.orEmpty()
        .mapValues { (_, response) -> (response as ReferenceOr.Value<Response>).value.description }

    fun text(status: HttpStatusCode, shared: String): String {
        val specific = declared[status.value]
        return if (specific.isNullOrBlank()) shared[0].uppercase() + shared.substring(1) else "$specific Also: $shared"
    }
    return {
        responses {
            if (own.requestBody != null) {
                error(
                    HttpStatusCode.BadRequest,
                    text(HttpStatusCode.BadRequest, "the body is not the expected JSON object (`invalid_request`).")
                )
            }
            if (!access.public) {
                error(
                    HttpStatusCode.Unauthorized,
                    text(
                        HttpStatusCode.Unauthorized,
                        "no credential, or an unknown, expired or revoked one (`unauthorized`).",
                    ),
                )
                error(
                    HttpStatusCode.Forbidden,
                    text(HttpStatusCode.Forbidden, "the credential may not call this operation (`forbidden`)."),
                )
            }
            if (HttpStatusCode.InternalServerError.value !in declared) {
                error(HttpStatusCode.InternalServerError, "An unexpected server error (`internal_error`). Retry later.")
            }
            default {
                description = "Any other error, in the same envelope."
                schema = jsonSchema<ApiError>()
            }
        }
    }
}

/**
 * A JSON request body.
 *
 * @param text What the body holds.
 * @param required Whether the body may be left out.
 */
inline fun <reified T : Any> Operation.Builder.body(text: String, required: Boolean = true) {
    requestBody {
        description = text
        this.required = required
        schema = jsonSchema<T>()
    }
}

/**
 * A success response with a JSON body.
 *
 * @param text What the answer means.
 * @param status Its status, 200 by default.
 */
inline fun <reified T : Any> Responses.Builder.ok(text: String, status: HttpStatusCode = HttpStatusCode.OK) {
    status {
        description = text
        schema = jsonSchema<T>()
    }
}

/**
 * A success response without a body.
 *
 * @param text What the answer means.
 * @param status Its status, 204 by default.
 */
fun Responses.Builder.empty(text: String, status: HttpStatusCode = HttpStatusCode.NoContent) {
    status { description = text }
}

/**
 * An error response in the `{"error": {"type", "message"}}` envelope.
 *
 * @param status Its status.
 * @param text When it is answered, with its `type`.
 */
fun Responses.Builder.error(status: HttpStatusCode, text: String) {
    status {
        description = text
        schema = jsonSchema<ApiError>()
    }
}

/**
 * A 400 in the error envelope.
 *
 * @param text When it is answered, with its `type`.
 */
fun Responses.Builder.badRequest(text: String) = error(HttpStatusCode.BadRequest, text)

/**
 * A 404 in the error envelope.
 *
 * @param text When it is answered, with its `type`.
 */
fun Responses.Builder.notFound(text: String) = error(HttpStatusCode.NotFound, text)

/**
 * A 409 in the error envelope.
 *
 * @param text When it is answered, with its `type`.
 */
fun Responses.Builder.conflict(text: String) = error(HttpStatusCode.Conflict, text)

/** The 429 of a costly read, bounded per caller. */
fun Responses.Builder.rateLimited() = error(
    HttpStatusCode.TooManyRequests,
    "Too many reads lately (`rate_limited`): nothing was read. A costly read is bounded per caller (a person, a " +
        "device, an agent or a service): calls per minute, reads and waits together, and calls at once, the waits " +
        "of `getGate` counted apart from the reads.",
)
