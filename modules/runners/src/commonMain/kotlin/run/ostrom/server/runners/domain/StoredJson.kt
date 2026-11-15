// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Stored JSON text (an event's data, a permission answer) as an object.
 *
 * @param text The stored text.
 * @return The object; null when the text is absent, is not JSON or is not an object.
 */
internal fun storedObject(text: String?): JsonObject? {
    if (text == null) return null
    return try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }
}
