package dev.ostrom.server.mcprelay.domain

import dev.ostrom.server.secrets.domain.SecretScrubber
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Removes secret values from a JSON tree (string values and keys), so the result stays valid JSON.
 *
 * @param secrets The values to remove.
 *
 * @return The scrubbed tree; this one when there is nothing to remove.
 */
fun JsonElement.scrubbed(secrets: List<String>): JsonElement {
    if (secrets.none { it.length >= SecretScrubber.MIN_LENGTH }) return this
    return when (this) {
        is JsonObject -> JsonObject(
            entries.associate { (key, value) ->
                SecretScrubber.scrub(key, secrets) to
                    value.scrubbed(secrets)
            },
        )
        is JsonArray -> JsonArray(map { it.scrubbed(secrets) })
        is JsonPrimitive -> if (isString) JsonPrimitive(SecretScrubber.scrub(content, secrets)) else this
    }
}
