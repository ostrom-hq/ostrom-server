// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.mcphost

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A string member, or null when it is absent or not a string.
 *
 * @param name The member.
 */
internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * A whole number member, or null when it is absent, a string or not whole.
 *
 * @param name The member.
 */
internal fun JsonObject.number(name: String): Long? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

/**
 * An object member, or null when it is absent or not an object.
 *
 * @param name The member.
 */
internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
