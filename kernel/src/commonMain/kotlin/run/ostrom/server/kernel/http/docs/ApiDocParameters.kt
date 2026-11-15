// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.http.docs

import io.ktor.openapi.Parameters
import io.ktor.openapi.jsonSchema

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
