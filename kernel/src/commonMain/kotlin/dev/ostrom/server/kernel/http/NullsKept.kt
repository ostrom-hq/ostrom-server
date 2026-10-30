// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.http

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonObject

/**
 * The serializer of an answer with fields the contract declares required and nullable. [OstromJson] omits every
 * null (`explicitNulls = false`), which suits the optional fields; this serializer writes `null` for each of [keys]
 * left out, so a client reads the field present and null, as the contract says. Reading is the generated
 * serializer's.
 *
 * Use it on the class, with its generated serializer kept: `@KeepGeneratedSerializer`,
 * `@Serializable(with = X.Answer::class)` and `object Answer : NullsKept<X>(generatedSerializer(), "a")`.
 *
 * @param serializer The class's generated serializer.
 * @param keys The JSON names of the fields to write as `null` when null.
 */
open class NullsKept<T : Any>(serializer: KSerializer<T>, private vararg val keys: String) :
    JsonTransformingSerializer<T>(serializer) {

    override fun transformSerialize(element: JsonElement): JsonElement {
        val fields = element.jsonObject
        return JsonObject(fields + keys.filterNot(fields::containsKey).associateWith { JsonNull })
    }
}
