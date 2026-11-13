// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.workflowruns.domain.models

import run.ostrom.core.api.models.Deliverable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What an agent node must hand in, checked before a delivery is accepted (TeamCode `Deliverables`, decision
 * `workflows-one-system` section 2 "Delivery"): an `artifact` id, an https `url` (on the deliverable's `domain` or a
 * subdomain of it when set), a `text`, or a `json` value matching the deliverable's schema subset.
 */
object Deliverables {
    /** The longest text delivered. */
    const val MAX_TEXT: Int = 32_000

    /** The longest JSON delivered, encoded. */
    const val MAX_JSON: Int = 64_000

    /** The longest address delivered. */
    const val MAX_URL: Int = 2_000

    /**
     * Checks a delivery against what its node asks for.
     *
     * @param deliverable What the node asks for.
     * @param output What is delivered: a string for `artifact`, `url` and `text`, any JSON value but a string for
     *   `json`.
     *
     * @return The text the visit keeps: the string itself, or the JSON encoded.
     *
     * @throws InvalidDeliverableException When it is not what the node asks for, with what to deliver instead.
     */
    fun validate(deliverable: Deliverable, output: JsonElement): String = when (deliverable.type) {
        ARTIFACT -> artifact(text(output, "an artifact id"))
        URL -> url(text(output, "an https address"), deliverable.domain)
        TEXT -> text(output, "text").also {
            if (it.isBlank() || it.length > MAX_TEXT) throw invalid("Deliver 1 to $MAX_TEXT characters of text.")
        }
        JSON -> json(output, deliverable.schema)
        else -> throw invalid("This step asks for a ${deliverable.type.take(ECHO)} deliverable, which nothing fits.")
    }

    private fun artifact(id: String): String =
        id.takeIf { ARTIFACT_ID.matches(it) } ?: throw invalid("Deliver the id of an artifact.")

    private fun json(output: JsonElement, schema: JsonElement?): String {
        if (output is JsonPrimitive && output.isString) {
            throw invalid("Deliver a JSON value, not a string holding JSON.")
        }
        val encoded = output.toString()
        if (encoded.length > MAX_JSON) throw invalid("Deliver at most $MAX_JSON characters of JSON.")
        schema?.let { check(it, output, "output") }
        return encoded
    }

    private fun text(output: JsonElement, what: String): String =
        (output as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalid("Deliver $what as a string.")

    private fun url(value: String, domain: String?): String =
        value.also { urlRefusal(it, domain)?.let { refusal -> throw invalid(refusal) } }

    /**
     * A small subset of JSON Schema: `type` (object, array, string, number, integer, boolean), an object's
     * `properties` and `required`, an array's `items`, and a string's `enum`. Anything else in the schema is ignored.
     *
     * @param schema The schema.
     * @param value The value.
     * @param path Where the value is, for the refusal.
     *
     * @throws InvalidDeliverableException When the value does not match.
     */
    fun check(schema: JsonElement, value: JsonElement, path: String) {
        val rules = schema as? JsonObject ?: return
        when ((rules["type"] as? JsonPrimitive)?.contentOrNull) {
            "object" -> obj(rules, value, path)
            "array" -> array(rules, value, path)
            "string" -> string(rules, value, path)
            "number" -> scalar(value, path, "a number") { it.doubleOrNull != null }
            "integer" -> scalar(value, path, "an integer") { it.longOrNull != null }
            "boolean" -> scalar(value, path, "true or false") { it.booleanOrNull != null }
        }
    }

    private fun obj(rules: JsonObject, value: JsonElement, path: String) {
        val obj = value as? JsonObject ?: throw invalid("$path must be an object.")
        names(rules["required"]).firstOrNull { it !in obj }?.let { throw invalid("$path.$it is required.") }
        (rules["properties"] as? JsonObject)?.forEach { (key, sub) -> obj[key]?.let { check(sub, it, "$path.$key") } }
    }

    private fun array(rules: JsonObject, value: JsonElement, path: String) {
        val array = value as? JsonArray ?: throw invalid("$path must be a list.")
        rules["items"]?.let { items -> array.forEachIndexed { i, element -> check(items, element, "$path[$i]") } }
    }

    private fun string(rules: JsonObject, value: JsonElement, path: String) {
        val string = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw invalid("$path must be a string.")
        val allowed = (rules["enum"] as? JsonArray)?.let { names(it) }
        if (allowed != null && string !in allowed) throw invalid("$path must be one of ${allowed.joinToString(", ")}.")
    }

    private fun scalar(value: JsonElement, path: String, what: String, fits: (JsonPrimitive) -> Boolean) {
        val primitive = (value as? JsonPrimitive)?.takeUnless { it.isString }
        if (primitive == null || !fits(primitive)) throw invalid("$path must be $what.")
    }

    private fun names(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}

private fun invalid(message: String) = InvalidDeliverableException(message)

// What is wrong with an address: it must be an https address of the characters an address may hold (no backslash a
// browser would read as a slash), without a user name, whose host is the domain or one of its subdomains.
private fun urlRefusal(value: String, domain: String?): String? {
    val authority = value.drop(HTTPS.length).substringBefore('/').substringBefore('?').substringBefore('#')
    val bracketed = authority.startsWith("[")
    val host = (if (bracketed) authority.substringBefore(']') + "]" else authority.substringBefore(':')).lowercase()
    val within = domain?.lowercase()?.trimEnd('.')
    return when {
        value.length > Deliverables.MAX_URL || !URL_CHARACTERS.matches(value) ->
            "Deliver an https address of at most ${Deliverables.MAX_URL} characters, without spaces."
        !value.startsWith(HTTPS, ignoreCase = true) -> "Deliver an https address."
        '@' in authority -> "Deliver an https address without a user name."
        !HOST.matches(host) -> "Deliver an https address with a host."
        within != null && host != within && !host.endsWith(".$within") -> "Deliver an address on ${within.take(ECHO)}."
        else -> null
    }
}

// The deliverable types, the scheme of an address, the forms of an artifact id, of an address and of a host, and the
// most of a value a refusal shows. Top-level, not in the object: a private property of an object has a getter Kover
// counts.
private const val ARTIFACT = "artifact"
private const val URL = "url"
private const val TEXT = "text"
private const val JSON = "json"
private const val HTTPS = "https://"
private const val ECHO = 200
private val ARTIFACT_ID = Regex("[A-Za-z0-9._:-]{1,64}")
private val URL_CHARACTERS = Regex("[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
private val HOST = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*|\\[[0-9a-f:.]+]")
