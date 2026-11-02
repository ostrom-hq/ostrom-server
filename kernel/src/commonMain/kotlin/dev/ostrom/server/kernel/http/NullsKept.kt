// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.http

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * The serializer of an answer with fields the contract declares required and nullable. [OstromJson] omits every
 * null (`explicitNulls = false`), which suits the optional fields; this serializer writes `null` for each of [paths]
 * left out, so a client reads the field present and null, as the contract says. Reading is the generated
 * serializer's.
 *
 * Use it on the class, with its generated serializer kept: `@KeepGeneratedSerializer`,
 * `@Serializable(with = X.Answer::class)` and `object Answer : NullsKept<X>(generatedSerializer(), "a")`. For a
 * model of ostrom-core, which the server cannot annotate, pass its `serializer()` and use the object where the
 * answer is written.
 *
 * @param serializer The class's generated serializer.
 * @param paths The fields to write as `null` when null, in the [withNulls] form.
 */
open class NullsKept<T : Any>(serializer: KSerializer<T>, vararg paths: String) :
    JsonTransformingSerializer<T>(serializer) {
    private val paths: List<String> = paths.asList()

    override fun transformSerialize(element: JsonElement): JsonElement = element.withNullsAt(paths)
}

/**
 * This element with `null` written for each of [paths] left out: the fields the contract declares required and
 * nullable, in a body built by hand or by a model the server cannot annotate.
 *
 * A path names a field (`repo`), a field of an object field (`limits.maxPeople`), or a field of each object of an
 * array (`edges.*.to`); it ends on a field. A field already there, null or not, is kept as it is; a path through a
 * field that is absent or not of the expected kind writes nothing.
 *
 * @param paths The fields.
 *
 * @return The element with the fields.
 */
fun JsonElement.withNulls(vararg paths: String): JsonElement = withNullsAt(paths.asList())

private fun JsonElement.withNullsAt(paths: List<String>): JsonElement =
    paths.fold(this) { element, path -> element.withNull(path.split('.')) }

private fun JsonElement.withNull(path: List<String>): JsonElement {
    val head = path.first()
    val rest = path.drop(1)
    return when {
        head == ANY_ELEMENT -> if (this is JsonArray) JsonArray(map { it.withNull(rest) }) else this
        this !is JsonObject -> this
        rest.isEmpty() -> if (containsKey(head)) this else JsonObject(this + (head to JsonNull))
        else -> get(head)?.let { JsonObject(this + (head to it.withNull(rest))) } ?: this
    }
}

private const val ANY_ELEMENT = "*"
