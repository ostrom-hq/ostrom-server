// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The text at a path of keys in a runtime's JSON answer (`metadata.resourceVersion`, `State.Status`).
 *
 * @param keys The keys, outermost first.
 *
 * @return The text, or null when a key is missing, a step is not an object, or the end is not a value.
 */
internal fun JsonElement.textAt(vararg keys: String): String? {
    var node: JsonElement? = this
    for (key in keys) node = (node as? JsonObject)?.get(key)
    return (node as? JsonPrimitive)?.content
}
