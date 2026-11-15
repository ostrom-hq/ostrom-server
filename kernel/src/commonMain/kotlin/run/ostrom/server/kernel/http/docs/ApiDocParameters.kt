// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.http.docs

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.Parameters
import io.ktor.openapi.Responses
import io.ktor.openapi.jsonSchema
import kotlinx.serialization.json.JsonObject

/**
 * A required path parameter.
 *
 * @param name The parameter, as the route names it.
 * @param text What it identifies.
 */
fun Parameters.Builder.pathId(name: String, text: String) {
    path(name) {
        description = text
        required = true
        schema = jsonSchema<String>()
    }
}

/**
 * An optional string query parameter.
 *
 * @param name The parameter.
 * @param text What it selects.
 */
fun Parameters.Builder.queryText(name: String, text: String) {
    query(name) {
        description = text
        schema = jsonSchema<String>()
    }
}

/**
 * An optional, repeatable string query parameter (`?name=a&name=b`): an array the client sends exploded, one
 * `name=value` per item.
 *
 * @param name The parameter.
 * @param text What its values select.
 */
fun Parameters.Builder.queryList(name: String, text: String) {
    query(name) {
        description = text
        explode = true
        schema = jsonSchema<List<String>>()
    }
}

/**
 * An optional integer query parameter.
 *
 * @param name The parameter.
 * @param text What it selects.
 */
fun Parameters.Builder.queryNumber(name: String, text: String) {
    query(name) {
        description = text
        schema = jsonSchema<Long>()
    }
}

/**
 * The optional `limit` of a list: an integer from 1 to [maximum], bounds the schema declares (second security audit of
 * the contract, F4: a client sees the page size the server enforces).
 *
 * @param text The page size, its default and what happens outside the bounds.
 * @param maximum The largest page the route serves.
 */
fun Parameters.Builder.queryLimit(text: String, maximum: Int) {
    query("limit") {
        description = text
        schema = jsonSchema<Long>().copy(minimum = 1.0, maximum = maximum.toDouble())
    }
}

/**
 * An optional boolean query parameter.
 *
 * @param name The parameter.
 * @param text What it selects.
 */
fun Parameters.Builder.queryFlag(name: String, text: String) {
    query(name) {
        description = text
        schema = jsonSchema<Boolean>()
    }
}

/**
 * A request header.
 *
 * @param name The header.
 * @param text What it carries.
 * @param required Whether it must be sent.
 */
fun Parameters.Builder.headerText(name: String, text: String, required: Boolean = false) {
    header(name) {
        description = text
        this.required = required
        schema = jsonSchema<String>()
    }
}

/** The `Ostrom-Org` header of an operation that acts in one organization, as the contract has always written it. */
fun Parameters.Builder.organizationHeader() = headerText(
    "Ostrom-Org",
    "The id of the organization the request acts in. Required when the signed-in person is a member of several " +
        "organizations; without it, a person with exactly one active membership acts in that organization. Missing " +
        "while several memberships are active, 409 `org_required` (`error.type`, the usual `ApiError` body); an " +
        "organization the person is not a member of, 404, as for anything of another organization. A token bound " +
        "to one organization (device, agent, runner, service key) acts in its own organization.",
)

/**
 * A response whose JSON body has no schema of its own: a provider's answer or error relayed in the provider's shape,
 * a JSON-RPC message. Any JSON object.
 *
 * @param status Its status.
 * @param text What the answer means.
 */
fun Responses.Builder.anyJson(status: HttpStatusCode, text: String) = ok<JsonObject>(text, status)

/**
 * A response that is an HTML page, not JSON: a page a browser shows at the end of a sign-in.
 *
 * @param text What the page is.
 * @param status Its status, 200 by default.
 */
fun Responses.Builder.html(text: String, status: HttpStatusCode = HttpStatusCode.OK) {
    status {
        description = text
        ContentType.Text.Html { schema = jsonSchema<String>() }
    }
}
